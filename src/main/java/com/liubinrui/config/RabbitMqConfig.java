package com.liubinrui.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMqConfig {

    // ================= 博客推送相关常量 =================
    public static final String BLOG_PUSH_EXCHANGE = "blog.push.exchange";
    public static final String BLOG_PUSH_SMALL_V_QUEUE = "blog.push.small.v.queue";
    public static final String BLOG_PUSH_MEDIUM_V_QUEUE = "blog.push.medium.v.queue";
    public static final String BLOG_PULL_AGGREGATION_QUEUE = "blog.pull.aggregation.queue";

    // 推送路由 Key
    public static final String PUSH_SMALL_V_ROUTING_KEY = "blog.push.small.v";
    public static final String PUSH_MEDIUM_V_ROUTING_KEY = "blog.push.medium.v";
    public static final String PUSH_AGGREGATION_ROUTING_KEY = "blog.pull.aggregation";

    // ================= 点赞相关常量 =================
    public static final String BLOG_LIKE_EXCHANGE = "blog.like.exchange";
    public static final String BLOG_LIKE_QUEUE = "blog.like.action.queue";
    public static final String BLOG_LIKE_ROUTING_KEY = "blog.like.action";

    // ================= 【新增】死信队列常量 (DLQ) =================
    // 1. 点赞死信
    public static final String BLOG_LIKE_DLX_EXCHANGE = "blog.like.dlx.exchange";
    public static final String BLOG_LIKE_DLX_QUEUE = "blog.like.dlx.queue";
    public static final String BLOG_LIKE_DLX_ROUTING_KEY = "blog.like.dlx";

    // 2. 推送死信 (为了简化，三个推送队列可以共用一个死信队列，也可以分开，这里演示共用一个)
    public static final String BLOG_PUSH_DLX_EXCHANGE = "blog.push.dlx.exchange";
    public static final String BLOG_PUSH_DLX_QUEUE = "blog.push.dlx.queue";
    public static final String BLOG_PUSH_DLX_ROUTING_KEY = "blog.push.dlx";

    /**
     * 配置 JSON 序列化器
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置 RabbitTemplate
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        rabbitTemplate.setMandatory(true); // 消息不可达时触发 ReturnListener
        return rabbitTemplate;
    }

    // ================= 博客推送交换机与队列配置 =================

    @Bean
    public TopicExchange blogPushExchange() {
        return new TopicExchange(BLOG_PUSH_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange blogPushDlxExchange() {
        return new DirectExchange(BLOG_PUSH_DLX_EXCHANGE, true, false);
    }

    // --- 小 V 队列 (带死信配置) ---
    @Bean
    public Queue smallVQueue() {
        Map<String, Object> args = new HashMap<>();
        // 绑定死信交换机
        args.put("x-dead-letter-exchange", BLOG_PUSH_DLX_EXCHANGE);
        // 绑定死信路由键
        args.put("x-dead-letter-routing-key", BLOG_PUSH_DLX_ROUTING_KEY);
        // 可选：消息在队列存活时间 (毫秒)，超时未消费也转死信 (例如 1 小时)
        // args.put("x-message-ttl", 3600000);
        return QueueBuilder.durable(BLOG_PUSH_SMALL_V_QUEUE).withArguments(args).build();
    }

    // --- 中 V 队列 (带死信配置) ---
    @Bean
    public Queue mediumVQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", BLOG_PUSH_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", BLOG_PUSH_DLX_ROUTING_KEY);
        return QueueBuilder.durable(BLOG_PUSH_MEDIUM_V_QUEUE).withArguments(args).build();
    }

    // --- 聚合队列 (带死信配置) ---
    @Bean
    public Queue pullAggregationQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", BLOG_PUSH_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", BLOG_PUSH_DLX_ROUTING_KEY);
        return QueueBuilder.durable(BLOG_PULL_AGGREGATION_QUEUE).withArguments(args).build();
    }

    // --- 死信队列本身 (不需要再配死信，否则死循环) ---
    @Bean
    public Queue blogPushDlxQueue() {
        return QueueBuilder.durable(BLOG_PUSH_DLX_QUEUE).build();
    }

    // --- 绑定关系 ---
    @Bean
    public Binding smallVBinding(TopicExchange blogPushExchange, Queue smallVQueue) {
        return BindingBuilder.bind(smallVQueue).to(blogPushExchange).with(PUSH_SMALL_V_ROUTING_KEY);
    }

    @Bean
    public Binding mediumVBinding(TopicExchange blogPushExchange, Queue mediumVQueue) {
        return BindingBuilder.bind(mediumVQueue).to(blogPushExchange).with(PUSH_MEDIUM_V_ROUTING_KEY);
    }

    @Bean
    public Binding pullAggregationBinding(TopicExchange blogPushExchange, Queue pullAggregationQueue) {
        return BindingBuilder.bind(pullAggregationQueue).to(blogPushExchange).with(PUSH_AGGREGATION_ROUTING_KEY);
    }

    @Bean
    public Binding blogPushDlxBinding(DirectExchange blogPushDlxExchange, Queue blogPushDlxQueue) {
        return BindingBuilder.bind(blogPushDlxQueue).to(blogPushDlxExchange).with(BLOG_PUSH_DLX_ROUTING_KEY);
    }


    // ================= 点赞交换机与队列配置 =================

    @Bean
    public TopicExchange blogLikeExchange() {
        return new TopicExchange(BLOG_LIKE_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange blogLikeDlxExchange() {
        return new DirectExchange(BLOG_LIKE_DLX_EXCHANGE, true, false);
    }

    // --- 点赞主队列 (带死信配置) ---
    @Bean
    public Queue blogLikeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", BLOG_LIKE_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", BLOG_LIKE_DLX_ROUTING_KEY);
        // 可选：设置重试间隔可以通过 TTL 配合多个队列实现，这里仅做基础死信
        return QueueBuilder.durable(BLOG_LIKE_QUEUE).withArguments(args).build();
    }

    // --- 点赞死信队列 ---
    @Bean
    public Queue blogLikeDlxQueue() {
        return QueueBuilder.durable(BLOG_LIKE_DLX_QUEUE).build();
    }

    // --- 绑定关系 ---
    @Bean
    public Binding blogLikeBinding(TopicExchange blogLikeExchange, Queue blogLikeQueue) {
        return BindingBuilder.bind(blogLikeQueue).to(blogLikeExchange).with(BLOG_LIKE_ROUTING_KEY);
    }

    @Bean
    public Binding blogLikeDlxBinding(DirectExchange blogLikeDlxExchange, Queue blogLikeDlxQueue) {
        return BindingBuilder.bind(blogLikeDlxQueue).to(blogLikeDlxExchange).with(BLOG_LIKE_DLX_ROUTING_KEY);
    }
}