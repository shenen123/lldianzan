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

    // 博客推送相关常量
    public static final String BLOG_PUSH_EXCHANGE = "blog.push.exchange";

    public static final String BLOG_PUSH_SMALL_V_QUEUE = "blog.push.small.v.queue";
    public static final String BLOG_PUSH_ACTIVE_QUEUE = "blog.push.active.queue";
    public static final String BLOG_PUSH_UNACTIVE_QUEUE = "blog.pull.unactive.queue";

    // 推送路由 Key
    public static final String PUSH_SMALL_V_ROUTING_KEY = "blog.push.small.v";
    public static final String PUSH_ACTIVE_ROUTING_KEY = "blog.push.active.v";
    public static final String PUSH_UNACTIVE_ROUTING_KEY = "blog.push.unactive.v";

    // 点赞相关常量
    public static final String BLOG_LIKE_EXCHANGE = "blog.like.exchange";

    public static final String BLOG_LIKE_QUEUE = "blog.like.queue";
    public static final String BLOG_UNLIKE_QUEUE = "blog.unlike.queue";
    public static final String BLOG_LIKE_ROUTING_KEY = "blog.like";
    public static final String BLOG_UNLIKE_ROUTING_KEY = "blog.unlike";
    // 关注相关常量
    public static final String FOLLOW_PUSH_EXCHANGE = "follow.push.exchange";

    public static final String ADD_FOLLOW_QUEUE = "add.follow.queue";
    public static final String CANCEL_FOLLOW_QUEUE = "cancel.follow.queue";
    public static final String ADD_FOLLOW_ROUTING_KEY = "add.follow";
    public static final String CANCEL_FOLLOW_ROUTING_KEY = "cancel.follow";
    // 死信队列常量 (DLQ)
    // 1. 点赞死信
    public static final String BLOG_LIKE_DLX_EXCHANGE = "blog.like.dlx.exchange";
    public static final String BLOG_LIKE_DLX_QUEUE = "blog.like.dlx.queue";
    public static final String BLOG_LIKE_DLX_ROUTING_KEY = "blog.like.dlx";

    // 2. 推送死信，三个推送队列可以共用一个死信队列
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

    // 博客推送交换机与队列配置

    @Bean
    public TopicExchange blogPushExchange() {
        return new TopicExchange(BLOG_PUSH_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange blogPushDlxExchange() {
        return new DirectExchange(BLOG_PUSH_DLX_EXCHANGE, true, false);
    }

    // 小V队列
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

    // 活跃粉丝队列
    @Bean
    public Queue activeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", BLOG_PUSH_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", BLOG_PUSH_DLX_ROUTING_KEY);
        return QueueBuilder.durable(BLOG_PUSH_ACTIVE_QUEUE).withArguments(args).build();
    }

    // 非活跃粉丝队列
    @Bean
    public Queue unactiveQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", BLOG_PUSH_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", BLOG_PUSH_DLX_ROUTING_KEY);
        return QueueBuilder.durable(BLOG_PUSH_UNACTIVE_QUEUE).withArguments(args).build();
    }

    // 死信队列本身，不需要再配死信，否则死循环
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
    public Binding activeBinding(TopicExchange blogPushExchange, Queue activeQueue) {
        return BindingBuilder.bind(activeQueue).to(blogPushExchange).with(PUSH_ACTIVE_ROUTING_KEY);
    }

    @Bean
    public Binding unactiveBinding(TopicExchange blogPushExchange, Queue unactiveQueue) {
        return BindingBuilder.bind(unactiveQueue).to(blogPushExchange).with(PUSH_UNACTIVE_ROUTING_KEY);
    }

    // 死信队列本身
    @Bean
    public Binding blogPushDlxBinding(DirectExchange blogPushDlxExchange, Queue blogPushDlxQueue) {
        return BindingBuilder.bind(blogPushDlxQueue).to(blogPushDlxExchange).with(BLOG_PUSH_DLX_ROUTING_KEY);
    }


    // 点赞交换机与队列配置

    @Bean
    public TopicExchange blogLikeExchange() {
        return new TopicExchange(BLOG_LIKE_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange blogLikeDlxExchange() {
        return new DirectExchange(BLOG_LIKE_DLX_EXCHANGE, true, false);
    }

    // 点赞主队列
    @Bean
    public Queue blogLikeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", BLOG_LIKE_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", BLOG_LIKE_DLX_ROUTING_KEY);
        // 可选：设置重试间隔可以通过 TTL 配合多个队列实现，这里仅做基础死信
        return QueueBuilder.durable(BLOG_LIKE_QUEUE).withArguments(args).build();
    }

    @Bean
    public Queue blogUnLikeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", BLOG_LIKE_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", BLOG_LIKE_DLX_ROUTING_KEY);
        // 可选：设置重试间隔可以通过 TTL 配合多个队列实现，这里仅做基础死信
        return QueueBuilder.durable(BLOG_UNLIKE_QUEUE).withArguments(args).build();
    }
    // 点赞死信队列
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
    public Binding blogUNLikeBinding(TopicExchange blogLikeExchange, Queue blogUnLikeQueue) {
        return BindingBuilder.bind(blogUnLikeQueue).to(blogLikeExchange).with(BLOG_UNLIKE_ROUTING_KEY);
    }
    @Bean
    public Binding blogLikeDlxBinding(DirectExchange blogLikeDlxExchange, Queue blogLikeDlxQueue) {
        return BindingBuilder.bind(blogLikeDlxQueue).to(blogLikeDlxExchange).with(BLOG_LIKE_DLX_ROUTING_KEY);
    }

}