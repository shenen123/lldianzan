package com.liubinrui.redis;

import cn.hutool.core.collection.CollectionUtil;
import com.liubinrui.model.dto.msg.PushMsgDTO;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
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

    // --- ZSet Key 定义 ---
    private static final String FOLLOW_RANK_ZSET_KEY = "follow:rank";

    public SetOperations<String, String> opsForSet() {
        return redisTemplate.opsForSet();
    }

    public void expire(String key, long timeout, TimeUnit timeUnit) {
        if (key == null || timeout <= 0) {
            return;
        }
        redisTemplate.expire(key, timeout, timeUnit);
    }

    public List<PushMsgDTO> readMsgQueue(Long followerId, int limit) {
        String key = "msg:queue:" + followerId;
        List<String> msgStrList = redisTemplate.opsForList().range(key, 0, limit - 1);
        if (CollectionUtil.isEmpty(msgStrList)) return Collections.emptyList();
        redisTemplate.opsForList().trim(key, limit, -1);
        return msgStrList.stream().map(PushMsgDTO::fromRedisString).collect(Collectors.toList());
    }

    /**
     * 【改造】缓存用户粉丝数 -> 写入 ZSet
     * Key: follow:rank, Member: userId, Score: count
     */
    public void cacheFollowerCount(Long userId, Long count) {
        if (userId == null || count == null) return;
        redisTemplate.opsForZSet().add(FOLLOW_RANK_ZSET_KEY, userId.toString(), count.doubleValue());
        // 注意：ZSet 通常不过期，除非有清理策略
    }

    /**
     * 【改造】获取用户粉丝数 -> 从 ZSet 获取 Score
     */
    public Long getFollowerCount(Long userId) {
        if (userId == null) return null;
        Double score = redisTemplate.opsForZSet().score(FOLLOW_RANK_ZSET_KEY, userId.toString());
        return score == null ? null : score.longValue();
    }

    /**
     * 【新增】获取明星用户候选列表 (粉丝数 >= threshold)
     * 利用 ZSet 范围查询，性能极高
     */
    public Set<Long> getStarUserCandidates(long threshold, int limit) {
        // ZREVRANGEBYSCORE follow:rank +inf threshold LIMIT 0 limit
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(
                        FOLLOW_RANK_ZSET_KEY,
                        (double) threshold,
                        Double.MAX_VALUE,
                        0,
                        limit
                );

        if (CollectionUtil.isEmpty(tuples)) {
            return Collections.emptySet();
        }

        return tuples.stream()
                .map(ZSetOperations.TypedTuple::getValue)
                .filter(s -> s != null)
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    /**
     * 缓存活跃粉丝列表 (保持不变)
     */
    public void cacheActiveFollowerIds(Long userId, Set<Long> followerIds) {
        if (CollectionUtil.isEmpty(followerIds)) return;
        String key = "follow:active:" + userId;
        redisTemplate.opsForSet().add(key, followerIds.stream().map(String::valueOf).toArray(String[]::new));
        redisTemplate.expire(key, 3600, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 获取活跃粉丝列表 (保持不变)
     */
    public Set<Long> getActiveFollowerIds(Long userId) {
        Set<String> idStrSet = redisTemplate.opsForSet().members("follow:active:" + userId);
        if (CollectionUtil.isEmpty(idStrSet)) return Collections.emptySet();
        return idStrSet.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    // ------------------- 推送相关 (保持不变) -------------------
    public void pushMsgToFollower(Long followerId, PushMsgDTO msg) {
        if (msg.getTimestamp() == null) {
            // 方案 A: 使用当前系统时间戳 (毫秒)
            msg.setTimestamp(System.currentTimeMillis());
        }
        String key = "msg:queue:" + followerId;
        redisTemplate.opsForList().leftPush(key, msg.toRedisString());
        redisTemplate.opsForList().trim(key, 0, 99);
    }

    public void addBlogToAggregation(Long userId, Long blogId, long timestamp) {
        String key = "blog:msg:" + userId;
        redisTemplate.opsForZSet().add(key, blogId.toString(), timestamp);
        redisTemplate.opsForZSet().removeRange(key, 0, -1001);
    }

    public Set<PushMsgDTO> pullBlogsFromAggregation(Long userId, long minTimestamp) {
        Set<String> blogIds = redisTemplate.opsForZSet().rangeByScore("blog:msg:" + userId, minTimestamp, System.currentTimeMillis());
        if (CollectionUtil.isEmpty(blogIds)) return Collections.emptySet();
        Set<PushMsgDTO> result = new HashSet<>();
        for (String blogId : blogIds) {
            PushMsgDTO dto = new PushMsgDTO();
            dto.setBlogId(Long.parseLong(blogId));
            dto.setSenderId(userId);
            Double score = redisTemplate.opsForZSet().score("blog:msg:" + userId, blogId);
            dto.setTimestamp(score != null ? score.longValue() : 0L);
            result.add(dto);
        }
        return result;
    }

    // ------------------- 关注关系 (保持不变) -------------------
    public boolean checkIsFollowed(Long userId, Long followerId) {
        String key = "follow:user:" + userId;
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, followerId.toString()));
    }

    public void addFollower(Long userId, Long followerId) {
        redisTemplate.opsForSet().add("follow:user:" + userId, followerId.toString());
        redisTemplate.opsForSet().add("follower:" + followerId, userId.toString());
    }

    public void removeFollower(Long userId, Long followerId) {
        redisTemplate.opsForSet().remove("follow:user:" + userId, followerId.toString());
        redisTemplate.opsForSet().remove("follower:" + followerId, userId.toString());
    }

    /**
     * 【改造】粉丝数 +1 -> ZSet increment
     */
    public void incrFollowerCount(Long userId) {
        if (userId == null) return;
        redisTemplate.opsForZSet().incrementScore(FOLLOW_RANK_ZSET_KEY, userId.toString(), 1.0);
    }

    /**
     * 【改造】粉丝数 -1 -> ZSet increment (负数)
     */
    public void decrFollowerCount(Long userId) {
        if (userId == null) return;
        Double newScore = redisTemplate.opsForZSet().incrementScore(FOLLOW_RANK_ZSET_KEY, userId.toString(), -1.0);
        // 防止分数小于 0
        if (newScore != null && newScore < 0) {
            redisTemplate.opsForZSet().remove(FOLLOW_RANK_ZSET_KEY, userId.toString());
        }
    }
}
//package com.liubinrui.redis;
//
//import cn.hutool.core.collection.CollectionUtil;
//import com.liubinrui.model.dto.msg.PushMsgDTO;
//import jakarta.annotation.Resource;
//import org.springframework.data.redis.core.SetOperations;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.stereotype.Component;
//
//import java.util.Collections;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//
//@Component
//public class RedisService {
//    @Resource(name = "stringRedisTemplate")
//    private StringRedisTemplate redisTemplate;
//
//    public SetOperations<String, String> opsForSet() {
//        return redisTemplate.opsForSet();
//    }
//
//    public void expire(String key, long timeout, TimeUnit timeUnit) {
//        if (key == null || timeout <= 0) {
//            return;
//        }
//        redisTemplate.expire(key, timeout, timeUnit);
//    }
//
//    /**
//     * 缓存用户粉丝数
//     */
//    public void cacheFollowerCount(Long userId, Long count) {
//        redisTemplate.opsForValue().set("follow:count:" + userId, count.toString(), 3600 * 24, java.util.concurrent.TimeUnit.SECONDS);
//    }
//
//    /**
//     * 获取用户粉丝数
//     */
//    public Long getFollowerCount(Long userId) {
//        String countStr = redisTemplate.opsForValue().get("follow:count:" + userId);
//        return countStr == null ? null : Long.parseLong(countStr);
//    }
//
//    /**
//     * 缓存活跃粉丝列表
//     */
//    public void cacheActiveFollowerIds(Long userId, Set<Long> followerIds) {
//        if (CollectionUtil.isEmpty(followerIds)) return;
//        String key = "follow:active:" + userId;
//        redisTemplate.opsForSet().add(key, followerIds.stream().map(String::valueOf).toArray(String[]::new));
//        redisTemplate.expire(key, 3600, java.util.concurrent.TimeUnit.SECONDS); // 1小时过期
//    }
//
//    /**
//     * 获取活跃粉丝列表
//     */
//    public Set<Long> getActiveFollowerIds(Long userId) {
//        Set<String> idStrSet = redisTemplate.opsForSet().members("follow:active:" + userId);
//        if (CollectionUtil.isEmpty(idStrSet)) return Collections.emptySet();
//        return idStrSet.stream().map(Long::valueOf).collect(Collectors.toSet());
//    }
//
//    // ------------------- 推送相关 -------------------
//    /**
//     * 推模式：发送消息到粉丝队列
//     */
//    public void pushMsgToFollower(Long followerId, PushMsgDTO msg) {
//        String key = "msg:queue:" + followerId;
//        // 左推（队列头），最多保留100条消息
//        redisTemplate.opsForList().leftPush(key, msg.toRedisString());
//        redisTemplate.opsForList().trim(key, 0, 99);
//        // 未读消息计数+1
//        redisTemplate.opsForHash().increment("msg:unread:" + followerId, msg.getSenderId().toString(), 1);
//    }
//
//    /**
//     * 拉模式：添加博客到博主聚合表（ZSet，按时间戳排序）
//     */
//    public void addBlogToAggregation(Long userId, Long blogId, long timestamp) {
//        String key = "blog:msg:" + userId;
//        redisTemplate.opsForZSet().add(key, blogId.toString(), timestamp);
//        // 保留最近1000条博客
//        redisTemplate.opsForZSet().removeRange(key, 0, -1001);
//    }
//
//    /**
//     * 粉丝拉取博主的最新博客
//     */
//    public Set<PushMsgDTO> pullBlogsFromAggregation(Long userId, long minTimestamp) {
//        Set<String> blogIds = redisTemplate.opsForZSet().rangeByScore("blog:msg:" + userId, minTimestamp, System.currentTimeMillis());
//        if (CollectionUtil.isEmpty(blogIds)) return Collections.emptySet();
//        Set<PushMsgDTO> result = new HashSet<>();
//        for (String blogId : blogIds) {
//            PushMsgDTO dto = new PushMsgDTO();
//            dto.setBlogId(Long.parseLong(blogId));
//            dto.setSenderId(userId);
//            dto.setTimestamp(redisTemplate.opsForZSet().score("blog:msg:" + userId, blogId).longValue());
//            result.add(dto);
//        }
//        return result;
//    }
//
//    /**
//     * 粉丝读取消息队列
//     */
//    public List<PushMsgDTO> readMsgQueue(Long followerId, int limit) {
//        String key = "msg:queue:" + followerId;
//        List<String> msgStrList = redisTemplate.opsForList().range(key, 0, limit - 1);
//        if (CollectionUtil.isEmpty(msgStrList)) return Collections.emptyList();
//        // 读取后删除已读消息（可选：也可标记已读）
//        redisTemplate.opsForList().trim(key, limit, -1);
//        return msgStrList.stream().map(PushMsgDTO::fromRedisString).collect(Collectors.toList());
//    }
//
//    /**
//     * 检查是否已关注
//     */
//    public boolean checkIsFollowed(Long userId, Long followerId) {
//        String key = "follow:user:" + userId;
//        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, followerId.toString()));
//    }
//
//    /**
//     * 关注：添加粉丝到Redis集合
//     */
//    public void addFollower(Long userId, Long followerId) {
//        // 被关注者的粉丝列表
//        redisTemplate.opsForSet().add("follow:user:" + userId, followerId.toString());
//        // 粉丝的关注列表
//        redisTemplate.opsForSet().add("follower:" + followerId, userId.toString());
//    }
//
//    /**
//     * 取消关注：移除Redis集合中的粉丝
//     */
//    public void removeFollower(Long userId, Long followerId) {
//        redisTemplate.opsForSet().remove("follow:user:" + userId, followerId.toString());
//        redisTemplate.opsForSet().remove("follower:" + followerId, userId.toString());
//    }
//
//    /**
//     * 粉丝数+1
//     */
//    public void incrFollowerCount(Long userId) {
//        String key = "follow:count:" + userId;
//        redisTemplate.opsForValue().increment(key, 1);
//    }
//
//    /**
//     * 粉丝数-1
//     */
//    public void decrFollowerCount(Long userId) {
//        String key = "follow:count:" + userId;
//        redisTemplate.opsForValue().decrement(key, 1);
//    }
//}
