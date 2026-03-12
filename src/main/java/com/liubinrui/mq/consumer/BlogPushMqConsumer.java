package com.liubinrui.mq.consumer;
import cn.hutool.core.collection.CollectionUtil;
import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.mapper.MessageMapper;
import com.liubinrui.model.dto.mq.BlogPushMqDTO;
import com.liubinrui.model.entity.Message;
import com.liubinrui.redis.RedisService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class BlogPushMqConsumer {

    @Resource
    private RedisService redisService;
    @Resource
    private MessageMapper messageMapper;

    /**
     * 消费小V推送消息（全量推活跃粉丝 + 消息表兜底）
     */

    // 【关键点 1】定义一个线程安全的静态计数器
    // 初始值为 0，程序重启后会重置，但在运行期间会一直累加
    private static final AtomicInteger TOTAL_COUNT = new AtomicInteger(0);

    @RabbitListener(queues = RabbitMqConfig.BLOG_PUSH_SMALL_V_QUEUE)
    public void consumeSmallVPush(com.liubinrui.model.dto.mq.BlogPushMqDTO mqDTO) {
        // 1. 计数器 +1，并获取当前数值
        int currentCount = TOTAL_COUNT.incrementAndGet();
        log.info("✅ [累计消费第 {} 条] 收到消息 -> ID: {}, Title: {}",
                currentCount, mqDTO.getBlogId(), mqDTO.getUserId());
            Long userId = mqDTO.getUserId();
            Long blogId = mqDTO.getBlogId();
            Set<Long> followerIds = mqDTO.getFollowerIds();
            log.info("开始处理小V推送，博主ID：{}，粉丝数：{}", userId, followerIds.size());

            if (CollectionUtil.isEmpty(followerIds)) return;

            // 1. 推送到Redis队列
            for (Long followerId : followerIds) {
                redisService.pushMsgToFollower(followerId, convertToPushMsgDTO(mqDTO));
            }

            // 2. 批量插入消息表（兜底）
            List<Message> messageList = new ArrayList<>();
            for (Long followerId : followerIds) {
                Message message = new Message();
                message.setReceiverId(followerId);
                message.setBlogId(blogId);
                message.setSenderId(userId);
                message.setMessageType(1); // 博客更新
                message.setIsRead(0);
                message.setCreateTime(LocalDateTime.now());
                messageList.add(message);
            }
            if (!messageList.isEmpty()) {
                messageMapper.batchInsert(messageList);
            }
            log.info("小V推送处理完成，博主ID：{}",userId);
    }

    /**
     * 消费中V活跃粉丝推送消息
     */
    @RabbitListener(queues = RabbitMqConfig.BLOG_PUSH_MEDIUM_V_QUEUE)
    public void consumeMediumVPush(BlogPushMqDTO mqDTO) {
        try {
            Long senderId = mqDTO.getUserId();
            Set<Long> activeFollowerIds = mqDTO.getFollowerIds();
            log.info("开始处理中V活跃粉丝推送，博主ID：{}，活跃粉丝数：{}", senderId, activeFollowerIds.size());

            if (CollectionUtil.isEmpty(activeFollowerIds)) return;

            // 推送到Redis队列
            for (Long followerId : activeFollowerIds) {
                redisService.pushMsgToFollower(followerId, convertToPushMsgDTO(mqDTO));
            }
            log.info("中V活跃粉丝推送处理完成，博主ID：{}", senderId);
        } catch (Exception e) {
            log.error("处理中V推送失败，博主ID：{}", mqDTO.getUserId(), e);
            throw e;
        }
    }

    /**
     * 消费聚合表写入消息（中V非活跃/大V）
     */
    @RabbitListener(queues = RabbitMqConfig.BLOG_PULL_AGGREGATION_QUEUE)
    public void consumePullAggregation(BlogPushMqDTO mqDTO) {
        try {
            Long senderId = mqDTO.getUserId();
            Long blogId = mqDTO.getBlogId();
            Long timestamp = mqDTO.getTimestamp();
            log.info("开始处理聚合表写入，博主ID：{}，博客ID：{}", senderId, blogId);

            // 写入聚合表
            redisService.addBlogToAggregation(senderId, blogId, timestamp);
            log.info("聚合表写入完成，博主ID：{}", senderId);
        } catch (Exception e) {
            log.error("处理聚合表写入失败，博主ID：{}", mqDTO.getUserId(), e);
            throw e;
        }
    }

    /**
     * 转换MQ DTO到原有PushMsgDTO
     */
    private com.liubinrui.model.dto.msg.PushMsgDTO convertToPushMsgDTO(BlogPushMqDTO mqDTO) {
        com.liubinrui.model.dto.msg.PushMsgDTO pushMsg = new com.liubinrui.model.dto.msg.PushMsgDTO();
        pushMsg.setBlogId(mqDTO.getBlogId());
        pushMsg.setSenderId(mqDTO.getUserId());
        pushMsg.setTimestamp(mqDTO.getTimestamp());
        return pushMsg;
    }
}
