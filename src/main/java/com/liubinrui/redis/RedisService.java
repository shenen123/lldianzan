package com.liubinrui.redis;

import cn.hutool.core.collection.CollectionUtil;
import com.liubinrui.model.dto.msg.PushMsgDTO;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class RedisService {
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    public SetOperations<String, String> opsForSet() {
        return redisTemplate.opsForSet();
    }

    public void expire(String key, long timeout, TimeUnit timeUnit) {
        if (key == null || timeout <= 0) {
            return;
        }
        redisTemplate.expire(key, timeout, timeUnit);
    }

    /**
     * 缓存用户粉丝数
     */
    public void cacheFollowerCount(Long userId, Long count) {
        redisTemplate.opsForValue().set("follow:count:" + userId, count.toString(), 3600 * 24, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 获取用户粉丝数
     */
    public Long getFollowerCount(Long userId) {
        String countStr = redisTemplate.opsForValue().get("follow:count:" + userId);
        return countStr == null ? null : Long.parseLong(countStr);
    }

    /**
     * 缓存活跃粉丝列表
     */
    public void cacheActiveFollowerIds(Long userId, Set<Long> followerIds) {
        if (CollectionUtil.isEmpty(followerIds)) return;
        String key = "follow:active:" + userId;
        redisTemplate.opsForSet().add(key, followerIds.stream().map(String::valueOf).toArray(String[]::new));
        redisTemplate.expire(key, 3600, java.util.concurrent.TimeUnit.SECONDS); // 1小时过期
    }

    /**
     * 获取活跃粉丝列表
     */
    public Set<Long> getActiveFollowerIds(Long userId) {
        Set<String> idStrSet = redisTemplate.opsForSet().members("follow:active:" + userId);
        if (CollectionUtil.isEmpty(idStrSet)) return Collections.emptySet();
        return idStrSet.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    // ------------------- 推送相关 -------------------
    /**
     * 推模式：发送消息到粉丝队列
     */
    public void pushMsgToFollower(Long followerId, PushMsgDTO msg) {
        String key = "msg:queue:" + followerId;
        // 左推（队列头），最多保留100条消息
        redisTemplate.opsForList().leftPush(key, msg.toRedisString());
        redisTemplate.opsForList().trim(key, 0, 99);
        // 未读消息计数+1
        redisTemplate.opsForHash().increment("msg:unread:" + followerId, msg.getSenderId().toString(), 1);
    }

    /**
     * 拉模式：添加博客到博主聚合表（ZSet，按时间戳排序）
     */
    public void addBlogToAggregation(Long userId, Long blogId, long timestamp) {
        String key = "blog:msg:" + userId;
        redisTemplate.opsForZSet().add(key, blogId.toString(), timestamp);
        // 保留最近1000条博客
        redisTemplate.opsForZSet().removeRange(key, 0, -1001);
    }

    /**
     * 粉丝拉取博主的最新博客
     */
    public Set<PushMsgDTO> pullBlogsFromAggregation(Long userId, long minTimestamp) {
        Set<String> blogIds = redisTemplate.opsForZSet().rangeByScore("blog:msg:" + userId, minTimestamp, System.currentTimeMillis());
        if (CollectionUtil.isEmpty(blogIds)) return Collections.emptySet();
        Set<PushMsgDTO> result = new HashSet<>();
        for (String blogId : blogIds) {
            PushMsgDTO dto = new PushMsgDTO();
            dto.setBlogId(Long.parseLong(blogId));
            dto.setSenderId(userId);
            dto.setTimestamp(redisTemplate.opsForZSet().score("blog:msg:" + userId, blogId).longValue());
            result.add(dto);
        }
        return result;
    }

    /**
     * 粉丝读取消息队列
     */
    public List<PushMsgDTO> readMsgQueue(Long followerId, int limit) {
        String key = "msg:queue:" + followerId;
        List<String> msgStrList = redisTemplate.opsForList().range(key, 0, limit - 1);
        if (CollectionUtil.isEmpty(msgStrList)) return Collections.emptyList();
        // 读取后删除已读消息（可选：也可标记已读）
        redisTemplate.opsForList().trim(key, limit, -1);
        return msgStrList.stream().map(PushMsgDTO::fromRedisString).collect(Collectors.toList());
    }

    /**
     * 检查是否已关注
     */
    public boolean checkIsFollowed(Long userId, Long followerId) {
        String key = "follow:user:" + userId;
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, followerId.toString()));
    }

    /**
     * 关注：添加粉丝到Redis集合
     */
    public void addFollower(Long userId, Long followerId) {
        // 被关注者的粉丝列表
        redisTemplate.opsForSet().add("follow:user:" + userId, followerId.toString());
        // 粉丝的关注列表
        redisTemplate.opsForSet().add("follower:" + followerId, userId.toString());
    }

    /**
     * 取消关注：移除Redis集合中的粉丝
     */
    public void removeFollower(Long userId, Long followerId) {
        redisTemplate.opsForSet().remove("follow:user:" + userId, followerId.toString());
        redisTemplate.opsForSet().remove("follower:" + followerId, userId.toString());
    }

    /**
     * 粉丝数+1
     */
    public void incrFollowerCount(Long userId) {
        String key = "follow:count:" + userId;
        redisTemplate.opsForValue().increment(key, 1);
    }

    /**
     * 粉丝数-1
     */
    public void decrFollowerCount(Long userId) {
        String key = "follow:count:" + userId;
        redisTemplate.opsForValue().decrement(key, 1);
    }
}
