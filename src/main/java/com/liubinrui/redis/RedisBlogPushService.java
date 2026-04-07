package com.liubinrui.redis;

import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.model.entity.Blog;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RedisBlogPushService {

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;
    private static final String INBOX_FOLLOWER_KEY = "inbox:follower:";
    private static final String OUTBOX_FOLLOWER_KEY = "outbox:follow:";
    private static final int BATCH_SIZE = 1000;


    /**
     * 推送博客到粉丝的收件箱（批量）
     * @param blogId      博客ID
     * @param followerIds 粉丝ID集合
     */
    public void pushToFan(Long blogId, Set<Long> followerIds) {
        long startTime = System.currentTimeMillis();
        List<Long> idList = new ArrayList<>(followerIds);
        try {
            for (int i = 0; i < idList.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, idList.size());
                List<Long> batch = idList.subList(i, end);
                pushBatchToFan(blogId, batch);
                log.info("推送博客到粉丝收件箱完成: blogId={}, 第{}批，粉丝数={}",
                        blogId, i, followerIds.size());
            }
            log.info("推送博客到粉丝收件箱完成: blogId={},粉丝数={}, 共耗时={}ms",
                    blogId, followerIds.size(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("推送博客到粉丝收件箱失败: blogId={}", blogId, e);
            throw new RuntimeException("Redis 推送失败", e);
        }
    }

    // 批量推送给粉丝
    private void pushBatchToFan(Long blogId, List<Long> batch) {
        int pipelineBatchSize = 1000;  // 每个 Pipeline 发送 1000 条

        // 外层循环：分批执行 Pipeline
        for (int i = 0; i < batch.size(); i += pipelineBatchSize) {
            int end = Math.min(i + pipelineBatchSize, batch.size());
            List<Long> subBatch = batch.subList(i, end);
            // 每个子批次单独执行一个 Pipeline
            executePipelineBatch(blogId, subBatch);
        }
    }
    private void executePipelineBatch(Long blogId, List<Long> batch) {
        redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
            byte[] blogByte = blogId.toString().getBytes(StandardCharsets.UTF_8);
            byte[] prefixBytes = INBOX_FOLLOWER_KEY.getBytes(StandardCharsets.UTF_8);
            long time = System.currentTimeMillis();
            for (Long followerId : batch) {
                byte[] followerIdBytes = followerId.toString().getBytes(StandardCharsets.UTF_8);
                byte[] keyBytes = new byte[prefixBytes.length + followerIdBytes.length];
                System.arraycopy(prefixBytes, 0, keyBytes, 0, prefixBytes.length);
                System.arraycopy(followerIdBytes, 0, keyBytes, prefixBytes.length, followerIdBytes.length);
                connection.zAdd(keyBytes, (double) time, blogByte);
            }
            return null;
        });
    }

    /**
     * 推送博客到博主的发件箱
     * @param followId 关注者ID（博主）
     * @param blogId   博客ID
     */
    public void pushToOutBox(Long followId, Long blogId) {
        if (followId == null || blogId == null) {
            log.warn("pushToOutBox 参数无效: followId={}, blogId={}", followId, blogId);
            return;
        }
        long time = System.currentTimeMillis();
        try {
            String key = OUTBOX_FOLLOWER_KEY + followId;
            String value = blogId.toString();
            Boolean result = redisTemplate.opsForZSet().add(key, value, (double) time);
            // 设置过期时间
            redisTemplate.expire(key, 30, TimeUnit.DAYS);
            log.info("推送博客到关注者发件箱: followId={}, blogId={}, 结果为{}", followId, blogId, result);
        } catch (Exception e) {
            log.error("推送博客到关注者发件箱失败: followId={}, blogId={}", followId, blogId, e);
            throw new RuntimeException("Redis 推送失败", e);
        }
    }

    public Set<Long> getFromInbox(Long followerId) {
        // 获取距今最近的100个
        Set<String> blogStr = redisTemplate.opsForZSet().reverseRange(INBOX_FOLLOWER_KEY + followerId, 0, 99);
        if (blogStr == null)
            return Collections.emptySet();
        else
            return blogStr.stream().map(Long::parseLong).collect(Collectors.toSet());
    }

    public Set<Long> getFromOutBox(Set<Long> followIds, Long time) {
        Set<Long> blogIds = new HashSet<>();
        redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
            for (Long followId : followIds) {
                Set<String> blogIdStr;
                blogIdStr = redisTemplate.opsForZSet().rangeByScore(OUTBOX_FOLLOWER_KEY + followId, time - 604800 * 1000L, time);
                if (blogIdStr == null)
                    blogIdStr = new HashSet<>();
                blogIds.addAll(blogIdStr.stream().map(Long::parseLong).collect(Collectors.toSet()));
            }
            return null;
        });
        return blogIds;
    }

    public void rebuildBox(Long followerId, List<Blog> blogList) {
        if (blogList == null || blogList.isEmpty()) {
            return;
        }

        String inboxKey = INBOX_FOLLOWER_KEY + followerId;
        byte[] inboxKeyBytes = inboxKey.getBytes(StandardCharsets.UTF_8);

        redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
            // 1. 先删除旧的 Key，确保重建的数据是纯净的（可选，但推荐）
            connection.del(inboxKeyBytes);

            // 2. 批量写入 ZSet
            for (Blog blog : blogList) {
                // 确保 score 是毫秒级时间戳
                double score = blog.getCreateTime().getTime();
                byte[] value = blog.getId().toString().getBytes(StandardCharsets.UTF_8);
                connection.zAdd(inboxKeyBytes, score, value);
            }
            // 3. 设置过期时间 (关键！),假设设置 7 天过期，单位是秒
            connection.expire(inboxKeyBytes, 60 * 60 * 24 * 30);

            return null;
        });

        log.info("用户 {} 收件箱重建完成，共 {} 条数据", followerId, blogList.size());
    }

}