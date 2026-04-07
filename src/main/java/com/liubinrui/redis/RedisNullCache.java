package com.liubinrui.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis空值缓存工具类
 * 用于防止缓存穿透，统一管理空值缓存
 */
@Slf4j
@Component
public class RedisNullCache {
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 空值标记
    private static final String NULL_MARKER = "NULL";
    // 默认空值缓存过期时间（秒）
    private static final long DEFAULT_NULL_TTL_SEC = 300; // 5分钟
    // 博客空值缓存前缀
    public static final String BLOG_NULL_PREFIX = "blog:null:";
    // 粉丝空值缓存前缀
    public static final String FANS_NULL_PREFIX = "fans:null:";
    // 搜索结果空值缓存前缀
    public static final String SEARCH_NULL_PREFIX = "search:null:";

    /**
     * 检查是否为空值缓存
     */
    public boolean isNull(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return NULL_MARKER.equals(value);
    }

    /**
     * 设置空值缓存
     */
    public void setNull(String key) {
        setNull(key, DEFAULT_NULL_TTL_SEC);
    }

    /**
     * 设置空值缓存（指定过期时间）
     */
    public void setNull(String key, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, NULL_MARKER, ttlSeconds, TimeUnit.SECONDS);
            log.info("Redis空值缓存写入: key={}, ttl={}s", key, ttlSeconds);
        } catch (Exception e) {
            log.info("Redis空值缓存写入失败: key={}", key, e);
        }
    }

    /**
     * 清除空值缓存
     */
    public void deleteNull(String key) {
        try {
            redisTemplate.delete(key);
            log.info("Redis空值缓存清除: key={}", key);
        } catch (Exception e) {
            log.info("Redis空值缓存清除失败: key={}", key, e);
        }
    }

    /**
     * 生成博客空值缓存Key
     */
    public String buildBlogNullKey(Long blogId) {

        return BLOG_NULL_PREFIX + blogId;
    }

    /**
     * 生成粉丝空值缓存Key
     */
    public String buildFansNullKey(Long followId,Long followerId) {
        return FANS_NULL_PREFIX + followId+":"+followerId;
    }

    /**
     * 生成搜索结果空值缓存Key
     */
    public String buildSearchNullKey(String searchKey) {
        return SEARCH_NULL_PREFIX + searchKey;
    }
}
