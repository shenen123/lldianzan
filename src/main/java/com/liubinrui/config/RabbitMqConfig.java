package com.liubinrui.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    // 博客推送交换机
    public static final String BLOG_PUSH_EXCHANGE = "blog.push.exchange";
    // 小V推模式队列
    public static final String BLOG_PUSH_SMALL_V_QUEUE = "blog.push.small.v.queue";
    // 中V活跃粉丝推送队列
    public static final String BLOG_PUSH_MEDIUM_V_QUEUE = "blog.push.medium.v.queue";
    // 聚合表写入队列（中V非活跃/大V）
    public static final String BLOG_PULL_AGGREGATION_QUEUE = "blog.pull.aggregation.queue";

    // --- 【新增】点赞相关配置 ---
    public static final String BLOG_LIKE_EXCHANGE = "blog.like.exchange";
    public static final String BLOG_LIKE_QUEUE = "blog.like.action.queue";
    public static final String BLOG_LIKE_ROUTING_KEY = "blog.like.action";
    /**
     * 配置JSON序列化器，替代默认JDK序列化
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
    /**
     * 注入RabbitTemplate，指定JSON序列化器（确保生产者用JSON发送）
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        // 核心：设置消息转换器为JSON
        rabbitTemplate.setMessageConverter(messageConverter());
        // 可选：消息路由失败时抛出异常，便于排查
        rabbitTemplate.setMandatory(true);
        return rabbitTemplate;
    }
    /**
     * 声明交换机（主题模式，灵活路由）
     */
    @Bean
    public TopicExchange blogPushExchange() {
        return new TopicExchange(BLOG_PUSH_EXCHANGE, true, false);
    }

    /**
     * 小V推送队列
     */
    @Bean
    public Queue smallVQueue() {
        return QueueBuilder.durable(BLOG_PUSH_SMALL_V_QUEUE).build();
    }

    /**
     * 中V活跃粉丝推送队列
     */
    @Bean
    public Queue mediumVQueue() {
        return QueueBuilder.durable(BLOG_PUSH_MEDIUM_V_QUEUE).build();
    }

    /**
     * 聚合表写入队列
     */
    @Bean
    public Queue pullAggregationQueue() {
        return QueueBuilder.durable(BLOG_PULL_AGGREGATION_QUEUE).build();
    }

    /**
     * 绑定小V队列到交换机
     */
    @Bean
    public Binding smallVBinding(TopicExchange blogPushExchange, Queue smallVQueue) {
        return BindingBuilder.bind(smallVQueue).to(blogPushExchange).with("blog.push.small.v");
    }

    /**
     * 绑定中V队列到交换机
     */
    @Bean
    public Binding mediumVBinding(TopicExchange blogPushExchange, Queue mediumVQueue) {
        return BindingBuilder.bind(mediumVQueue).to(blogPushExchange).with("blog.push.medium.v");
    }

    /**
     * 绑定聚合表队列到交换机
     */
    @Bean
    public Binding pullAggregationBinding(TopicExchange blogPushExchange, Queue pullAggregationQueue) {
        return BindingBuilder.bind(pullAggregationQueue).to(blogPushExchange).with("blog.pull.aggregation");
    }
    /**
     * 声明点赞交换机
     */
    @Bean
    public TopicExchange blogLikeExchange() {
        return new TopicExchange(BLOG_LIKE_EXCHANGE, true, false);
    }

    /**
     * 声明点赞动作队列
     */
    @Bean
    public Queue blogLikeQueue() {
        return QueueBuilder.durable(BLOG_LIKE_QUEUE).build();
    }

    /**
     * 绑定点赞队列到交换机
     */
    @Bean
    public Binding blogLikeBinding(TopicExchange blogLikeExchange, Queue blogLikeQueue) {
        return BindingBuilder.bind(blogLikeQueue).to(blogLikeExchange).with(BLOG_LIKE_ROUTING_KEY);
    }
}
