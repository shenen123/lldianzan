package com.liubinrui.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liubinrui.common.ErrorCode;
import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.exception.BusinessException;
import com.liubinrui.exception.ThrowUtils;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.dto.mq.BlogPushMqDTO;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.redis.RedisNullCache;
import com.liubinrui.redis.RedisUserFollowService;
import com.liubinrui.service.BlogPushService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class BlogPushServiceImpl extends ServiceImpl<BlogMapper, Blog> implements BlogPushService {
    @Autowired
    private UserFollowMapper userFollowMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisUserFollowService redisUserFollowService;
    @Autowired
    private RedisNullCache redisNullCache;
    @Autowired
    private RedissonClient redissonClient;

    // 重构缓存
    @Override
    public void asyncPushBlogToFollowers(Blog blog) {
        ThrowUtils.throwIf(blog == null, ErrorCode.PARAMS_ERROR);

        String blogNullKey = redisNullCache.buildBlogNullKey(blog.getId());
        if (redisNullCache.isNull(blogNullKey)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "博客不存在");
        }

        Long followId = blog.getUserId();
        Long blogId = blog.getId();
        // 直接判断是不是明星
        if (!redisUserFollowService.isStar(followId)) {
            // 小V：推送给所有粉丝
            pushSV(followId, blogId);
        } else {
            // 中大V：区分活跃和不活跃粉丝
            pushZDV(followId, blogId);
        }
    }

    private void pushSV(Long followId, Long blogId) {
        log.info("开始推送给小V博主粉丝");
        // 获取全部粉丝
        Set<Long> fansIdsSet = redisUserFollowService.getFansIds(followId);
        if (fansIdsSet.isEmpty()) {
            // 重构缓存
            boolean result = rebuildFansCount(followId);
            if (!result)
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "重构缓存失败");
        }
        // 批量推送
        BlogPushMqDTO blogPushMqDTO = new BlogPushMqDTO();
        blogPushMqDTO.setBlogId(blogId);
        blogPushMqDTO.setFollowId(followId);
        blogPushMqDTO.setTimestamp(System.currentTimeMillis());
        blogPushMqDTO.setFollowerIds(fansIdsSet);

        rabbitTemplate.convertAndSend(RabbitMqConfig.BLOG_PUSH_EXCHANGE, RabbitMqConfig.PUSH_SMALL_V_ROUTING_KEY, blogPushMqDTO);
    }

    private Boolean rebuildFansCount(Long followId) {
        String lockKey = "lock:rebuild:fansCount:" + followId;
        RLock lock = redissonClient.getLock(lockKey);
        int count = 0;
        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 二次检查
                    count = redisUserFollowService.getFansCount(followId);
                    if (count == -1) {
                        count = userFollowMapper.countFollowerByUserId(followId);
                        if (count == 0)
                            log.info("用户没有粉丝");
                        boolean result = redisUserFollowService.redisBuildCache(followId,count);
                        if (!result)
                            return false;
                    }
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("重建粉丝数缓存失败", e);
        }
        return true;
    }

    private void pushZDV(Long followId, Long blogId) {
        log.info("开始推送给中大V博主粉丝");
         // 这里是限定活跃粉丝是100个，但是下面是1000个
        Set<Long> activeIds = redisUserFollowService.getActiveFanIds(followId);
        boolean hasActiveFans = false;

        if (activeIds.isEmpty()) {
            // 尝试重建活跃粉丝缓存
            activeIds = rebuildActiveFans(followId);
            if (!activeIds.isEmpty()) {
                hasActiveFans = true;
            }
        } else {
            hasActiveFans = true;
        }
        // 推送给活跃粉丝
        if (hasActiveFans) {
            BlogPushMqDTO activePushDTO = new BlogPushMqDTO();
            activePushDTO.setBlogId(blogId);
            activePushDTO.setFollowId(followId);
            activePushDTO.setTimestamp(System.currentTimeMillis());
            activePushDTO.setFollowerIds(activeIds);
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.BLOG_PUSH_EXCHANGE,
                    RabbitMqConfig.PUSH_ACTIVE_ROUTING_KEY,
                    activePushDTO
            );
        }

        // 给不活跃粉丝只发送一次
        BlogPushMqDTO inactivePushDTO = new BlogPushMqDTO();
        inactivePushDTO.setBlogId(blogId);
        inactivePushDTO.setFollowId(followId);
        inactivePushDTO.setTimestamp(System.currentTimeMillis());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.BLOG_PUSH_EXCHANGE,
                RabbitMqConfig.PUSH_UNACTIVE_ROUTING_KEY,
                inactivePushDTO
        );
    }

    private Set<Long> rebuildActiveFans(Long followId) {
        String lockKey = "lock:push:zdv:" + followId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 二次检查
                    Set<Long> activeIds = redisUserFollowService.getActiveFanIds(followId);
                    if (!activeIds.isEmpty()) {
                        return activeIds;
                    }
                    List<Map<String, Object>> activeMap = userFollowMapper.getActiveFansIds(followId, 100);
                    if (!activeMap.isEmpty()) {
                        return redisUserFollowService.rebuildActiveFans(followId, activeMap);
                    }
                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("获取锁失败，跳过活跃粉丝推送: followId={}", followId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取锁被中断", e);
        }
        return Collections.emptySet();
    }
}

