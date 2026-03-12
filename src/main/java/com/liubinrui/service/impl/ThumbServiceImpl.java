package com.liubinrui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.heavykeeper.HeavyKeeperRateLimiter;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.mapper.ThumbMapper;
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
import org.redisson.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        int slot = userId.hashCode() & 3; // 分片 0~3，防大Key
        String userLikeRedisKey = USER_LIKE_PREFIX + slot + ":" + userId;
        // HeavyKeeper 高频检测
        Boolean localLiked = userLikeLocalCache.getIfPresent(localKey);
        String hkKey = userId + "_" + blogId;
        if (heavyKeeperShards[slot].isOverLimit(hkKey)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "操作过于频繁，请5分钟后重试（单日对同一内容操作上限10次）");
        }
        ThrowUtils.throwIf(Boolean.TRUE.equals(localLiked), ErrorCode.OPERATION_ERROR, "用户已点赞，不能重复点赞");
        // ========== 2. Redis 缓存 ==========
        RMap<String, String> userLikeMap = redissonClient.getMap(userLikeRedisKey);
        boolean redisHas = userLikeMap.containsKey(blogIdStr);
        if (redisHas) {
            userLikeLocalCache.put(localKey, true);
            ThrowUtils.throwIf(redisHas, ErrorCode.OPERATION_ERROR, "用户已点赞，不能重复点赞");
        }
        // ========== 3. 分布式锁 ==========
        String lockKey = LOCK_LIKE_PREFIX + userId + ":" + blogId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 等待100ms，持有1s，非常适合点赞
            boolean locked = lock.tryLock(100, 1000, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "请求太频繁，请稍后重试");
            }

            // 二次校验,防止多机点赞
            if (userLikeMap.containsKey(blogIdStr)) {
                userLikeLocalCache.put(localKey, true);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户已点赞，不能重复点赞");
            }

            // ========== 4. DB 插入点赞记录 ==========
            Thumb thumb = new Thumb();
            thumb.setUserId(userId);
            thumb.setBlogId(blogId);
            thumbMapper.insert(thumb);
            blogService.update().setSql("thumb_count = thumb_count + 1").eq("id", blogId).update();
            // ========== 5. Redis 计数 +1（异步） ==========
            // 1. 定义变量类型改为 RAtomicLong
            RAtomicLong atomicCount = redissonClient.getAtomicLong(blog_LIKE_COUNT_PREFIX + blogId);
            // 2. 直接调用 (和之前一样，但绝对不会报错)
            atomicCount.incrementAndGetAsync();
            // ========== 6. Redis Hash 写入（异步） ==========
            userLikeMap.putAsync(blogIdStr, thumb.getId().toString());
            userLikeMap.expireAsync(30, TimeUnit.DAYS);
            // ========== 7. 本地缓存 ==========
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
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        Long blogId = deleteRequest.getBlogId();
        User loginUser = userService.getLoginUser(request);
        Long userId = loginUser.getId();
        String blogIdStr = blogId.toString();
        String localKey = userId + "_" + blogId;
        int slot = userId.hashCode() & 3;
        String userLikeRedisKey = USER_LIKE_PREFIX + slot + ":" + userId;
        //
        String hkKey = userId + "_" + blogId;
        if (heavyKeeperShards[slot].isOverLimit(hkKey)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "操作过于频繁，请5分钟后重试（单日对同一内容操作上限10次）");
        }
        RMap<String, String> userLikeMap = redissonClient.getMap(userLikeRedisKey);
        if (!userLikeMap.containsKey(blogIdStr)) {
            userLikeLocalCache.invalidate(localKey);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未点赞，无法取消");
        }

        String lockKey = LOCK_LIKE_PREFIX + userId + ":" + blogId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean locked = lock.tryLock(100, 1000, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "请求太频繁，请稍后重试");
            }
            // DB删除
            thumbMapper.deleteByUserIdAnalogId(userId, blogId);
            blogService.update().setSql("thumb_count = thumb_count - 1").eq("id", blogId).update();
            // 计数-1
            // 1. 定义变量类型改为 RAtomicLong
            RAtomicLong atomicCount = redissonClient.getAtomicLong(blog_LIKE_COUNT_PREFIX + blogId);
            // 取消点赞时
            atomicCount.decrementAndGetAsync();
            // Redis删除
            userLikeMap.removeAsync(blogIdStr);
            // 本地失效
            userLikeLocalCache.invalidate(localKey);
            // ========== 新增：8. 重置 HeavyKeeper 计数 ==========
            heavyKeeperShards[slot].reset(hkKey);
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
