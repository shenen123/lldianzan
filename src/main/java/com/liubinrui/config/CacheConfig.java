package com.liubinrui.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * 本地缓存：key=userId_blogId，value=是否已点赞(true/false)
     */
    @Bean
    public Cache<String, Boolean> userLikeLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(10000)      // 最多缓存1万条
                .expireAfterWrite(3, TimeUnit.MINUTES) // 3分钟过期
                .softValues()
                .build();
    }
}
