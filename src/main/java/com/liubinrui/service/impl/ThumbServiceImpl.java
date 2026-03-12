package com.liubinrui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.enums.BlogActionEnum;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.heavykeeper.HeavyKeeperRateLimiter;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.mapper.ThumbMapper;
import com.liubinrui.model.dto.mq.BlogLikeMqDTO;
import com.liubinrui.model.dto.thumb.ThumbAddRequest;
import com.liubinrui.model.dto.thumb.ThumbDeleteRequest;
import com.liubinrui.model.dto.thumb.ThumbQueryRequest;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.Thumb;
import com.liubinrui.model.entity.User;
import com.liubinrui.model.vo.ThumbVO;

import com.liubinrui.service.BlogService;
import com.liubinrui.service.ThumbService;
import com.liubinrui.service.UserService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
@Slf4j
@Service
public class ThumbServiceImpl extends ServiceImpl<ThumbMapper, Thumb> implements ThumbService {
    @Autowired
    private UserService userService;
    @Resource
    private RedissonClient redissonClient;
    @Autowired
    private BlogMapper blogMapper;
    @Resource
    private Cache<String, Boolean> userLikeLocalCache;
    private static final String USER_LIKE_PREFIX = "user:like:";
    private static final String LOCK_LIKE_PREFIX = "lock:like:";
    private static final String blog_LIKE_COUNT_PREFIX = "blog:like:count:";
    @Autowired
    private ThumbMapper thumbMapper;
    @Autowired
    @Lazy
    private BlogService blogService;
    @Autowired
    private HotBlogService hotBlogService;
    @Resource
    private RabbitTemplate rabbitTemplate;
    private HeavyKeeperRateLimiter[] heavyKeeperShards=new HeavyKeeperRateLimiter[4];

    // ========== 初始化 HeavyKeeper 分片（大厂标准：单例+分片） ==========
    @PostConstruct
    public void init() {
        // 初始化4个分片的HeavyKeeper，基础阈值=5次/5分钟（大厂经验值）
        for (int i = 0; i < 4; i++) {
            heavyKeeperShards[i] = new HeavyKeeperRateLimiter(5);
        }
    }
    @Override
    public void validThumb(Thumb thumb, boolean add) {

    }

    @Override
    public Boolean doThumb(ThumbAddRequest doThumbRequest, HttpServletRequest request) {
        Long blogId = doThumbRequest.getBlogId();
        User loginUser = userService.getLoginUser(request);
        Long userId = loginUser.getId();

        String blogIdStr = blogId.toString();
        String localKey = userId + "_" + blogId;
        int slot = userId.hashCode() & 3;
        String userLikeRedisKey = USER_LIKE_PREFIX + slot + ":" + userId;

        // 1. HeavyKeeper 高频检测 (保留，防刷)
        String hkKey = userId + "_" + blogId;
        if (heavyKeeperShards[slot].isOverLimit(hkKey)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "操作过于频繁，请5分钟后重试");
        }

        // 2. 本地缓存快速判重 (保留)
        Boolean localLiked = userLikeLocalCache.getIfPresent(localKey);
        if (Boolean.TRUE.equals(localLiked)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户已点赞");
        }

        // 3. Redis 快速判重 (保留)
        RMap<String, String> userLikeMap = redissonClient.getMap(userLikeRedisKey);
        if (userLikeMap.containsKey(blogIdStr)) {
            userLikeLocalCache.put(localKey, true);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户已点赞");
        }

        // 4. 分布式锁 (保留，防止并发重复提交)
        String lockKey = LOCK_LIKE_PREFIX + userId + ":" + blogId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取锁
            if (!lock.tryLock(100, 1000, TimeUnit.MILLISECONDS)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "请求太频繁，请稍后重试");
            }

            // 5. 二次校验 (双重检查锁模式)
            if (userLikeMap.containsKey(blogIdStr)) {
                userLikeLocalCache.put(localKey, true);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户已点赞");
            }

            // --- 【核心修改】准备发送 MQ 消息，不再直接操作 DB ---

            // 5.1 查询博主 ID (需要知道是谁的博客，以便消费者更新博主的总获赞数)
            // 注意：这里需要查一次 DB 或缓存获取 blog 的 userId。如果 blog 对象有缓存最好。
            Blog blog = blogService.getById(blogId);
            if (blog == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "博客不存在");
            }

            // 5.2 构建消息
            BlogLikeMqDTO mqDTO = new BlogLikeMqDTO();
            mqDTO.setUserId(userId);
            mqDTO.setBlogId(blogId);
            mqDTO.setTargetUserId(blog.getUserId());
            mqDTO.setType(1); // 1 代表点赞

            // 5.3 发送消息
            log.info("🚀 [点赞] 发送 MQ 消息 -> User:{}, Blog:{}", userId, blogId);
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.BLOG_LIKE_EXCHANGE,
                    RabbitMqConfig.BLOG_LIKE_ROUTING_KEY,
                    mqDTO
            );

            // 6. Redis 状态标记 (异步/非阻塞)
            // 即使 MQ 发送成功，我们也要先标记用户已点赞，防止用户瞬间再次点击
            // 使用 putAsync 不阻塞当前线程
            userLikeMap.putAsync(blogIdStr, "1");
            userLikeMap.expireAsync(30, TimeUnit.DAYS);

            // 7. 本地缓存标记
            userLikeLocalCache.put(localKey, true);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "系统异常");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Boolean undoThumb(ThumbDeleteRequest deleteRequest, HttpServletRequest request) {
        Long blogId = deleteRequest.getBlogId();
        User loginUser = userService.getLoginUser(request);
        Long userId = loginUser.getId();

        String blogIdStr = blogId.toString();
        String localKey = userId + "_" + blogId;
        int slot = userId.hashCode() & 3;
        String userLikeRedisKey = USER_LIKE_PREFIX + slot + ":" + userId;

        // 1. HeavyKeeper 检测 (保留)
        String hkKey = userId + "_" + blogId;
        if (heavyKeeperShards[slot].isOverLimit(hkKey)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "操作过于频繁");
        }

        // 2. Redis 判空 (保留)
        RMap<String, String> userLikeMap = redissonClient.getMap(userLikeRedisKey);
        if (!userLikeMap.containsKey(blogIdStr)) {
            userLikeLocalCache.invalidate(localKey);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未点赞，无法取消");
        }

        // 3. 分布式锁 (保留)
        String lockKey = LOCK_LIKE_PREFIX + userId + ":" + blogId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(100, 1000, TimeUnit.MILLISECONDS)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "请求太频繁");
            }

            // 4. 二次校验
            if (!userLikeMap.containsKey(blogIdStr)) {
                userLikeLocalCache.invalidate(localKey);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "未点赞，无法取消");
            }

            // --- 【核心修改】发送 MQ 消息 ---

            // 4.1 查询博主 ID
            Blog blog = blogService.getById(blogId);
            if (blog == null) {
                // 博客不存在时，直接清理本地状态并返回，不需要发 MQ
                userLikeMap.removeAsync(blogIdStr);
                userLikeLocalCache.invalidate(localKey);
                return true;
            }

            // 4.2 构建消息
            BlogLikeMqDTO mqDTO = new BlogLikeMqDTO();
            mqDTO.setUserId(userId);
            mqDTO.setBlogId(blogId);
            mqDTO.setTargetUserId(blog.getUserId());
            mqDTO.setType(0); // 0 代表取消点赞

            // 4.3 发送消息
            log.info("🚀 [取消点赞] 发送 MQ 消息 -> User:{}, Blog:{}", userId, blogId);
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.BLOG_LIKE_EXCHANGE,
                    RabbitMqConfig.BLOG_LIKE_ROUTING_KEY,
                    mqDTO
            );

            // 5. 清理 Redis 状态 (异步)
            userLikeMap.removeAsync(blogIdStr);

            // 6. 清理本地缓存
            userLikeLocalCache.invalidate(localKey);

            // 7. 重置 HeavyKeeper (可选，取消点赞通常不需要重置频次限制，看业务需求)
            // heavyKeeperShards[slot].reset(hkKey);

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "系统异常");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Scheduled(cron = "0 0/5 * * * ?")
    public void syncLikeCount2DB() {
        // 1.获取所有需要同步的博客ID
        List<Long> blogIdList = blogMapper.selectAllblogId();

        List<Blog> updateList = new ArrayList<>();
        for (Long blogId : blogIdList) {
            RBucket<Integer> bucket = redissonClient.getBucket(blog_LIKE_COUNT_PREFIX + blogId);
            Integer count = bucket.get();
            if (count == null) continue;

            Blog blog = new Blog();
            blog.setId(blogId);
            blog.setThumbCount(count);
            updateList.add(blog);
        }

        // 2.批量更新DB
        if (!updateList.isEmpty()) {
            blogMapper.updateBatchLikeCount(updateList);
        }
    }

    @Override
    public QueryWrapper<Thumb> getQueryWrapper(ThumbQueryRequest thumbQueryRequest) {

        ThrowUtils.throwIf(thumbQueryRequest == null, ErrorCode.PARAMS_ERROR);
        QueryWrapper<Thumb> queryWrapper = new QueryWrapper<>();
        Long id = thumbQueryRequest.getId();
        Long userId = thumbQueryRequest.getUserId();
        Long blogId = thumbQueryRequest.getBlogId();
        int current = thumbQueryRequest.getCurrent();
        int pageSize = thumbQueryRequest.getPageSize();
        String sortField = thumbQueryRequest.getSortField();
        String sortOrder = thumbQueryRequest.getSortOrder();
        //精确查询
        queryWrapper.eq(userId != null && userId > 0, "user_id", userId);
        queryWrapper.eq(blogId != null && blogId > 0, "blog_id", blogId);
        queryWrapper.eq(id != null && id > 0, "id", id);
        return queryWrapper;
    }


    @Override
    public ThumbVO getThumbVO(Thumb thumb, HttpServletRequest request) {
        return null;
    }

    @Override
    public Page<ThumbVO> getThumbVOPage(Page<Thumb> thumbPage, HttpServletRequest request) {
        return null;
    }

    @Override
    public Boolean hasThumb(Long blogId, Long userId) {
        return null;
    }
}
