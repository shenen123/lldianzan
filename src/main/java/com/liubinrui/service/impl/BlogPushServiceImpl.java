package com.liubinrui.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.mapper.BlogMapper;
import com.liubinrui.mapper.MessageMapper;
import com.liubinrui.mapper.UserFollowMapper;
import com.liubinrui.model.dto.mq.BlogPushMqDTO;
import com.liubinrui.model.dto.msg.PushMsgDTO;
import com.liubinrui.model.entity.Blog;
import com.liubinrui.model.entity.Message;
import com.liubinrui.redis.RedisService;
import com.liubinrui.service.BlogPushService;
import com.liubinrui.service.BlogService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class BlogPushServiceImpl extends ServiceImpl<BlogMapper, Blog> implements BlogPushService {
    @Resource
    private RedisService redisService;
    @Resource
    private UserFollowMapper userFollowMapper;
    @Resource
    private MessageMapper messageMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    // 配置参数
    @Value("${blog.follow.active-days}")
    private Integer activeDays;
    @Value("${blog.push.small-v}")
    private Long smallVThreshold;
    @Value("${blog.push.medium-v}")
    private Long mediumVThreshold;

    // 初始化时设置消息转换器
    @PostConstruct
    public void initRabbitTemplate() {
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
    }

    /**
     * 异步推送（核心入口）
     */
    @Async
    public void asyncPushBlogToFollowers(Blog blog) {
        try {
            Long userId = blog.getUserId();
            Long blogId = blog.getId();
            long timestamp = System.currentTimeMillis();
            log.info("开始推送博客，博主ID：{}，博客ID：{}", userId, blogId);

            // 1. 获取粉丝数
            Long followerCount = redisService.getFollowerCount(userId);
            if (followerCount == null) {
                followerCount = userFollowMapper.countFollowerByUserId(userId);
                redisService.cacheFollowerCount(userId, followerCount);
                log.info("从数据库获取粉丝数：{}", followerCount);
            } else {
                log.info("从Redis获取粉丝数：{}", followerCount);
            }

            // 2. 分级推送
//            PushMsgDTO pushMsg = new PushMsgDTO();
//            pushMsg.setBlogId(blogId);
//            pushMsg.setSenderId(userId);
//            pushMsg.setTimestamp(timestamp);
            BlogPushMqDTO mqDTO = new BlogPushMqDTO();
            mqDTO.setBlogId(blogId);
            mqDTO.setUserId(userId);
            mqDTO.setTimestamp(timestamp);
            if (followerCount <= smallVThreshold) {
                log.info("小V推送模式，粉丝数：{}", followerCount);

                // 1. 获取全量粉丝ID (假设这里有 3 个: [101, 102, 103])
                Set<Long> allFollowerIds = userFollowMapper.listAllFollowerIds(userId);

                // 2. 【关键修改】遍历每一个粉丝，单独发送一条消息
                for (Long singleFollowerId : allFollowerIds) {

                    // A. 创建一个新的 DTO 对象 (每次循环都要 new，保证数据隔离)
                    BlogPushMqDTO mqDTO1 = new BlogPushMqDTO();

                    // B. 设置公共字段 (根据你的业务补充完整)
                    mqDTO1.setUserId(userId);
                    mqDTO1.setBlogId(blogId);          // 假设你有这个字段
                    mqDTO1.setPushType("small_v");

                    // C. 【核心步骤】创建一个只包含当前这 1 个粉丝 ID 的 Set
                    Set<Long> singleFanSet = new java.util.HashSet<>();
                    singleFanSet.add(singleFollowerId);

                    // D. 将这个“单人集合”设置到 DTO 中
                    mqDTO1.setFollowerIds(singleFanSet);

                    // E. 发送消息 (循环几次，这里就执行几次)
                    log.info("🚀 正在向粉丝 [{}] 发送独立推送消息...", singleFollowerId);
                    rabbitTemplate.convertAndSend(
                            RabbitMqConfig.BLOG_PUSH_EXCHANGE,
                            "blog.push.small.v",
                            mqDTO1
                    );
                }
            } else if (followerCount <= mediumVThreshold) {
                log.info("中V混合模式，粉丝数：{}", followerCount);
                mqDTO.setPushType("medium_v");
                // 获取活跃粉丝ID
                Set<Long> activeFollowerIds = redisService.getActiveFollowerIds(userId);
                if (CollectionUtil.isEmpty(activeFollowerIds)) {
                    activeFollowerIds = userFollowMapper.listActiveFollowerIds(userId, activeDays);
                    redisService.cacheActiveFollowerIds(userId, activeFollowerIds);
                }
                mqDTO.setFollowerIds(activeFollowerIds);
                // 1. 发送活跃粉丝推送消息到中V队列
                rabbitTemplate.convertAndSend(RabbitMqConfig.BLOG_PUSH_EXCHANGE, "blog.push.medium.v", mqDTO);
                // 2. 发送聚合表写入消息（非活跃粉丝拉取）
                rabbitTemplate.convertAndSend(RabbitMqConfig.BLOG_PUSH_EXCHANGE, "blog.pull.aggregation", mqDTO);
                //mixMode(userId, pushMsg);
            } else {
                log.info("大V拉模式，粉丝数：{}", followerCount);
                //pullMode(userId, pushMsg);
                mqDTO.setPushType("big_v");
                // 发送聚合表写入消息
                rabbitTemplate.convertAndSend(RabbitMqConfig.BLOG_PUSH_EXCHANGE, "blog.pull.aggregation", mqDTO);
            }
            log.info("博客推送完成，博主ID：{}", userId);
        } catch (Exception e) {
            log.error("博客推送失败，博主ID：{}", blog.getUserId(), e);
        }
    }

    /**
     * 推模式（小V）：全量推活跃粉丝 + 消息表兜底
     */
    public void pushMode(Long userId, PushMsgDTO pushMsg) {
        // 1. 获取全量粉丝（小V粉丝量小，可全量）
        Set<Long> followerIds = userFollowMapper.listAllFollowerIds(userId);
        if (CollectionUtil.isEmpty(followerIds)) return;

        // 2. 推送到Redis队列
        for (Long followerId : followerIds) {
            redisService.pushMsgToFollower(followerId, pushMsg);
        }

        // 3. 批量插入消息表（兜底，防止Redis数据丢失）
        List<Message> messageList = new ArrayList<>();
        for (Long followerId : followerIds) {
            Message message = new Message();
            message.setReceiverId(followerId);
            message.setBlogId(pushMsg.getBlogId());
            message.setSenderId(pushMsg.getSenderId());
            message.setMessageType(1); // 博客更新
            message.setIsRead(0);
            message.setCreateTime(LocalDateTime.now());
            messageList.add(message);
        }
        if (!messageList.isEmpty()) {
            messageMapper.batchInsert(messageList);
        }
    }

    /**
     * 混合模式（中V）：推活跃粉丝 + 拉非活跃粉丝
     */
    public void mixMode(Long userId, PushMsgDTO pushMsg) {
        // 1. 获取活跃粉丝
        Set<Long> activeFollowerIds = redisService.getActiveFollowerIds(userId);
        if (CollectionUtil.isEmpty(activeFollowerIds)) {
            activeFollowerIds = userFollowMapper.listActiveFollowerIds(userId, activeDays);
            redisService.cacheActiveFollowerIds(userId, activeFollowerIds);
        }

        // 2. 推活跃粉丝
        for (Long followerId : activeFollowerIds) {
            redisService.pushMsgToFollower(followerId, pushMsg);
        }

        // 3. 写入聚合表（非活跃粉丝拉取）
        redisService.addBlogToAggregation(userId, pushMsg.getBlogId(), pushMsg.getTimestamp());
    }

    /**
     * 拉模式（大V）：仅写入聚合表
     */
    private void pullMode(Long userId, PushMsgDTO pushMsg) {
        redisService.addBlogToAggregation(userId, pushMsg.getBlogId(), pushMsg.getTimestamp());
    }
}

