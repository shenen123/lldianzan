package com.liubinrui.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * 配置 RedisTemplate Bean
     * 解决报错：required a bean of type 'RedisTemplate' that could not be found
     */
    @Bean
    public RedisTemplate<String, Long> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Long> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key 序列化
        template.setKeySerializer(new StringRedisSerializer());
        // Hash Key 序列化
        template.setHashKeySerializer(new StringRedisSerializer());

        // 使用 GenericToStringSerializer<Long>，它会将 Long 直接转为字符串 "100"
        template.setValueSerializer(new GenericToStringSerializer<>(Long.class));
        template.setHashValueSerializer(new GenericToStringSerializer<>(Long.class));

        template.afterPropertiesSet();
        return template;
    }
}