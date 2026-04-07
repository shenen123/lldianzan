package com.liubinrui.redis;

import com.alibaba.fastjson.JSON;
import com.liubinrui.model.entity.Blog;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.N;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RedisHotBlogService {
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    private static final String REDIS_HOT_SCORE_PREFIX = "blog:hot:score:";
    private static final String HOT_BLOG_PREFIX = "hot:blog:";
    private static final String NORMAL_BLOG_PREFIX = "normal:blog";
    private static final String REDIS_HOT_RANK_KEY = "blog:hot:rank";
    private static final int REDIS_HOT_SCORE_EXPIRE_DAYS = 7;

    //            blogId.toString(), // ARGV[1]
//                    String.valueOf(increment), // ARGV[2]
//            String.valueOf(REDIS_HOT_SCORE_EXPIRE_DAYS) // ARGV[3]
    private static final String UPDATE_HOT_SCORE_SCRIPT_STR =
            "local newScore = redis.call('incrby', KEYS[1], tonumber(ARGV[2])) " +
                    "if newScore < 0 then " +
                    "    redis.call('set', KEYS[1], 0) " +
                    "    newScore = 0 " +
                    "end " +
                    "redis.call('zadd', KEYS[2], newScore, ARGV[1]) " +
                    "redis.call('expire', KEYS[1], tonumber(ARGV[3]) * 86400) " +
                    "redis.call('expire', KEYS[2], tonumber(ARGV[3]) * 86400) " +
                    "return newScore";
    // TODO 这里需要截断数据
    private static final RedisScript<Long> UPDATE_HOT_SCORE_SCRIPT =
            RedisScript.of(UPDATE_HOT_SCORE_SCRIPT_STR, Long.class);

    public Long updateHotScore(Long blogId, Integer increment) {
        String scoreKey = REDIS_HOT_SCORE_PREFIX + blogId;
        List<String> keys = Arrays.asList(scoreKey, REDIS_HOT_RANK_KEY);
        try {
            // 执行脚本
            Long result = redisTemplate.execute(UPDATE_HOT_SCORE_SCRIPT,
                    keys,
                    blogId.toString(), // ARGV[1]
                    String.valueOf(increment), // ARGV[2]
                    String.valueOf(REDIS_HOT_SCORE_EXPIRE_DAYS) // ARGV[3]
            );
            log.info("Redis更新博客热度成功: blogId={}, newHotScore={}",
                    blogId, result);
            return result;
        } catch (Exception e) {
            log.info("Redis更新博客热度失败: blogId={}", blogId, e);
            throw new RuntimeException("更新博客热度失败", e);
        }
    }

    public void loadHotScoreToRedis(Map<Long, Integer> blogMap) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Map.Entry<Long, Integer> entry : blogMap.entrySet()) {
                Long blogId = entry.getKey();
                Integer hotScore = entry.getValue();

                // 这里是一个key,一个value
                String key = REDIS_HOT_SCORE_PREFIX + blogId;
                String value = hotScore.toString();
                connection.set(
                        key.getBytes(StandardCharsets.UTF_8),
                        value.getBytes(StandardCharsets.UTF_8)
                );

                byte[] rankKey = REDIS_HOT_RANK_KEY.getBytes(StandardCharsets.UTF_8);
                byte[] member = blogId.toString().getBytes(StandardCharsets.UTF_8);
                connection.zAdd(rankKey, hotScore.doubleValue(), member);
            }
            return null;
        });
    }

    public void loadNormalBlogToRedis(Map<Long, Blog> blogNormalMap) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Map.Entry<Long, Blog> entry : blogNormalMap.entrySet()) {
                Long blogId = entry.getKey();
                Blog normalBlog = entry.getValue();

                // 这里是一个key,一个value
                String key = NORMAL_BLOG_PREFIX + blogId;
                byte[] valueBytes = JSON.toJSONBytes(normalBlog);
                connection.set(
                        key.getBytes(StandardCharsets.UTF_8),
                        valueBytes
                );
            }
            return null;
        });
    }

    public void loadHotBlogToRedis(Map<Long, Blog> blogMap) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Map.Entry<Long, Blog> entry : blogMap.entrySet()) {
                Long blogId = entry.getKey();
                Blog hotBlog = entry.getValue();

                // 这里是一个key,一个value
                String key = HOT_BLOG_PREFIX + blogId;
                byte[] valueBytes = JSON.toJSONBytes(hotBlog);
                connection.set(
                        key.getBytes(StandardCharsets.UTF_8),
                        valueBytes
                );
            }
            return null;
        });
    }

    public List<Blog> getHotBlog(List<Long> blogIds) {
        if (blogIds == null || blogIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建所有 key
        List<String> keys = blogIds.stream()
                .map(id -> HOT_BLOG_PREFIX + id)
                .collect(Collectors.toList());

        // 批量获取
        List<String> blogStrs = redisTemplate.opsForValue().multiGet(keys);

        // 解析结果
        List<Blog> blogList = new ArrayList<>();
        for (String blogStr : blogStrs) {
            if (blogStr != null && !blogStr.isEmpty()) {
                blogList.add(JSON.parseObject(blogStr, Blog.class));
            }
        }

        return blogList;
    }

    public List<Blog> getNormalBlog(List<Long> blogIds) {
        if (blogIds == null || blogIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建所有 key
        List<String> keys = blogIds.stream()
                .map(id -> NORMAL_BLOG_PREFIX + id)
                .collect(Collectors.toList());

        // 批量获取
        List<String> blogStrs = redisTemplate.opsForValue().multiGet(keys);

        // 解析结果
        List<Blog> blogList = new ArrayList<>();
        for (String blogStr : blogStrs) {
            if (blogStr != null && !blogStr.isEmpty()) {
                blogList.add(JSON.parseObject(blogStr, Blog.class));
            }
        }

        return blogList;
    }

    public void clearAllHotScore() {
        // 就是这里是否要清空博客的数据，正常不太需要，因为一般ID和内容不修改，内容要修改
        log.info("已清空Redis相关的热门博客数据");
        // 1. 删除排行榜 ZSet
        redisTemplate.delete(REDIS_HOT_RANK_KEY);

        // 2. 删除所有博客热度值的 String key（使用 SCAN 或 KEYS）
        Set<String> keys = redisTemplate.keys(REDIS_HOT_SCORE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        Set<String> blogSet = redisTemplate.keys(HOT_BLOG_PREFIX + "*");
        if (blogSet != null && !blogSet.isEmpty()) {
            redisTemplate.delete(blogSet);
        }

        Set<String> blogSets = redisTemplate.keys(NORMAL_BLOG_PREFIX + "*");
        if (blogSets != null && !blogSets.isEmpty()) {
            redisTemplate.delete(blogSets);
        }
        log.info("已清空所有博客热度相关 Redis 数据");
    }


}