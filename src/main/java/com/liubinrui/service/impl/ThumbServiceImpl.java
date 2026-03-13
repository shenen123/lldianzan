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

    @Override
    public void validThumb(Thumb thumb, boolean add) {

    }

    @Override
    public Boolean doThumb(ThumbAddRequest doThumbRequest, HttpServletRequest request) {
        Long blogId = doThumbRequest.getBlogId();
        User loginUser = userService.getLoginUser(request);
        Long userId = loginUser.getId();

        String blogIdStr = blogId.toString();
        // 优化：哈希槽位计算保持不变，利于分片
        int slot = userId.hashCode() & 3;
        String userLikeRedisKey = USER_LIKE_PREFIX + slot + ":" + userId;

        RMap<String, String> userLikeMap = redissonClient.getMap(userLikeRedisKey);
        // 1. Redis 快速判重 (第一道防线)
        // 建议这里也用同步 get，保证读取最新状态
        if (userLikeMap.containsKey(blogIdStr)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "您已经点过赞了");
        }

        // 2. 分布式锁 (防止并发竞态条件)
        String lockKey = LOCK_LIKE_PREFIX + userId + ":" + blogId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 优化：开启看门狗 (-1)，防止业务执行时间过长导致锁自动释放
            if (!lock.tryLock(100, -1, TimeUnit.MILLISECONDS)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "手速太快啦，请稍后再试");
            }

            // 3. 双重检查 (Double Check) - 拿到锁后必须再查一次
            if (userLikeMap.containsKey(blogIdStr)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "您已经点过赞了");
            }

            // 4. 获取博客信息 (建议在此处增加博客存在性的缓存判断，防止穿透)
            Blog blog = blogService.getById(blogId);
            if (blog == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "博客不存在");
            }

            // 5. 构建并发送 MQ 消息 (核心业务)
            BlogLikeMqDTO mqDTO = new BlogLikeMqDTO();
            mqDTO.setUserId(userId);
            mqDTO.setBlogId(blogId);
            mqDTO.setTargetUserId(blog.getUserId());
            mqDTO.setType(1);

            log.info("🚀 [点赞] 准备发送 MQ -> User:{}, Blog:{}", userId, blogId);

            // 关键：同步发送，确保消息到达 Broker。如果失败，直接抛异常，后续 Redis 不会写入
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMqConfig.BLOG_LIKE_EXCHANGE,
                        RabbitMqConfig.BLOG_LIKE_ROUTING_KEY,
                        mqDTO
                );
            } catch (Exception e) {
                log.error("[点赞] MQ 发送失败，操作回滚", e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "点赞服务暂时不可用");
            }

            // 6. 写入 Redis 标记 (成功后的“存证”)
            // 优化：改为同步写入，确保数据强一致性。Redis 写入极快，性能损耗可忽略
            userLikeMap.put(blogIdStr, "1");
            userLikeMap.expire(30, TimeUnit.DAYS);

            log.info("✅ [点赞] 成功 -> User:{}, Blog:{}", userId, blogId);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[点赞] 线程中断", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙");
        } finally {
            // 安全解锁
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
        int slot = userId.hashCode() & 3;
        String userLikeRedisKey = USER_LIKE_PREFIX + slot + ":" + userId;

        RMap<String, String> userLikeMap = redissonClient.getMap(userLikeRedisKey);

        // 1. Redis 快速判空
        if (!userLikeMap.containsKey(blogIdStr)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "您尚未点赞，无需取消");
        }

        // 2. 分布式锁
        String lockKey = LOCK_LIKE_PREFIX + userId + ":" + blogId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 优化：启用看门狗 (-1)，防止业务超时导致锁失效
            if (!lock.tryLock(100, -1, TimeUnit.MILLISECONDS)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "操作太频繁，请稍后再试");
            }

            // 3. 双重检查
            if (!userLikeMap.containsKey(blogIdStr)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "您尚未点赞，无需取消");
            }

            // 4. 查询博客信息
            Blog blog = blogService.getById(blogId);

            // 修正逻辑：如果博客不存在，说明数据可能不一致，不要盲目删 Redis，直接报错或尝试修复
            if (blog == null) {
                log.error("⚠️ [取消点赞] 博客不存在但 Redis 有标记 -> User:{}, Blog:{}", userId, blogId);
                // 方案 A (推荐): 抛出异常，让用户刷新，人工介入或定时任务清理脏数据
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "博客不存在，无法操作，请刷新页面");

                // 方案 B (激进): 强制清理 Redis，但不发 MQ (可能导致 DB 多一条脏记录，需依赖定期对账)
                // userLikeMap.remove(blogIdStr);
                // return true;
            }

            // 5. 构建并发送 MQ 消息 (核心业务：通知数据库减计数)
            BlogLikeMqDTO mqDTO = new BlogLikeMqDTO();
            mqDTO.setUserId(userId);
            mqDTO.setBlogId(blogId);
            mqDTO.setTargetUserId(blog.getUserId());
            mqDTO.setType(0); // 0 代表取消点赞

            log.info("🚀 [取消点赞] 准备发送 MQ -> User:{}, Blog:{}", userId, blogId);

            // 关键：同步发送，确保消息到达
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMqConfig.BLOG_LIKE_EXCHANGE,
                        RabbitMqConfig.BLOG_LIKE_ROUTING_KEY,
                        mqDTO
                );
            } catch (Exception e) {
                log.error("[取消点赞] MQ 发送失败，操作回滚", e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "取消点赞失败，请稍后重试");
            }

            // 6. 清理 Redis 标记 (同步删除，确保一致性)
            // 只有 MQ 发送成功后，才删除本地标记
            userLikeMap.remove(blogIdStr);

            log.info("✅ [取消点赞] 成功 -> User:{}, Blog:{}", userId, blogId);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[取消点赞] 线程中断", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙");
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
