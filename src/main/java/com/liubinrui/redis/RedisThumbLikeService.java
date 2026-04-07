package com.liubinrui.redis;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@Slf4j
public class RedisThumbLikeService {
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    private static final String USER_LIKE_SET_KEY = "user:like:";          // 用户的点赞列表
    private static final String BLOG_LIKE_SET_KEY = "blog:like:";           // 博客的点赞用户列表
    private static final String BLOG_LIKE_COUNT_ZSET_KEY = "blog:like:count:";

    private static final String ATOMIC_LIKE_SCRIPT_STR =
            // KEYS[1] = userLikeKey (e.g., "user:like:1001")
            // KEYS[2] = blogLikeKey (e.g., "blog:like:2002")
            // KEYS[3] = blogLikeCountKey (e.g., "blog:like:count:2002")
            // ARGV[1] = userId (用户ID)
            // ARGV[2] = blogId (博客ID)
            "local isLike=redis.call('sismember',KEYS[1],ARGV[2]) " +
                    " if isLike==1 then return -1 end " +
                    " redis.call('sadd',KEYS[1],ARGV[2]) " +
                    " redis.call('sadd',KEYS[2],ARGV[1]) " +
                    " local newCount=redis.call('incr',KEYS[3]) " +
                    " return newCount";
    private static final RedisScript<Long> ATOMIC_LIKE_SCRIPT =
            RedisScript.of(ATOMIC_LIKE_SCRIPT_STR, Long.class);

    private static final String ATOMIC_UNLIKE_SCRIPT_STR =
            // KEYS[1] = userLikeKey (e.g., "user:like:1001")
            // KEYS[2] = blogLikeKey (e.g., "blog:like:2002")
            // KEYS[3] = blogLikeCountKey (e.g., "blog:like:count:2002")
            // ARGV[1] = userId (用户ID)
            // ARGV[2] = blogId (博客ID)
            "local isLike=redis.call('sismember',KEYS[1],ARGV[2]) " +
                    " if isLike==0 then return -1 end " +
                    " redis.call('srem',KEYS[1],ARGV[2]) " +
                    " redis.call('srem',KEYS[2],ARGV[1]) " +
                    " local newCount=redis.call('decr',KEYS[3]) " +
                    " if newCount<0 then redis.call('set',KEYS[3],0) newCount=0 end " +
                    " return newCount";
    private static final RedisScript<Long> ATOMIC_UNLIKE_SCRIPT =
            RedisScript.of(ATOMIC_UNLIKE_SCRIPT_STR, Long.class);

    public Long LuaLike(Long userId, Long blogId) {
        String userLikeKey = USER_LIKE_SET_KEY + userId;
        String blogLikeKey = BLOG_LIKE_SET_KEY + blogId;
        String blogLikeCountKey = BLOG_LIKE_COUNT_ZSET_KEY + blogId;

        try {
            // 执行脚本
            Long result = redisTemplate.execute(ATOMIC_LIKE_SCRIPT,
                    Arrays.asList(userLikeKey, blogLikeKey, blogLikeCountKey),
                    userId.toString(),  // ARGV[1]
                    blogId.toString()   // ARGV[2]
            );
            if (result != -1)
                log.info("Redis点赞成功: userId={}, blogId={}, newCount={}",
                        userId, blogId, result);
            return result;
        } catch (Exception e) {
            log.info("Redis点赞失败: userId={}, blogId={}", userId, blogId, e);
            throw new RuntimeException("点赞操作失败", e);
        }
    }

    public Long LuaUnLike(Long userId, Long blogId) {
        String userLikeKey = USER_LIKE_SET_KEY + userId;
        String blogLikeKey = BLOG_LIKE_SET_KEY + blogId;
        String blogLikeCountKey = BLOG_LIKE_COUNT_ZSET_KEY + blogId;

        try {
            // 执行脚本
            Long result = redisTemplate.execute(ATOMIC_UNLIKE_SCRIPT,
                    Arrays.asList(userLikeKey, blogLikeKey, blogLikeCountKey),
                    userId.toString(),  // ARGV[1]
                    blogId.toString()   // ARGV[2]
            );
            if (result != -1)
                log.info("Redis取消点赞成功: userId={}, blogId={}, newCount={}",
                        userId, blogId, result);
            return result;
        } catch (Exception e) {
            log.info("Redis取消点赞失败: userId={}, blogId={}", userId, blogId, e);
            throw new RuntimeException("点赞操作失败", e);
        }
    }
}
