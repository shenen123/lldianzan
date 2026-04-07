package com.liubinrui.mq.consumer;

import java.util.Set;

import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.model.dto.mq.BlogPushMqDTO;
import com.liubinrui.redis.RedisBlogPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class BlogPushMqConsumer {

    @Autowired
    private RedisBlogPushService redisBlogPushService;

    // TODO 处理消息插入到redis,inbox和outbox

    @RabbitListener(queues = RabbitMqConfig.BLOG_PUSH_SMALL_V_QUEUE)
    public void consumeSmallV(BlogPushMqDTO dto) {
        if (dto == null || dto.getBlogId() == null||dto.getBlogId()<=0) {
            log.warn("消费小V推送消息失败: dto为空");
            return;
        }

        Long blogId = dto.getBlogId();
        Set<Long> followerIds = dto.getFollowerIds();

        if (followerIds == null || followerIds.isEmpty()) {
            log.warn("小V推送消息粉丝列表为空: blogId={}", blogId);
            return;
        }

        redisBlogPushService.pushToFan(blogId, followerIds);
    }


    @RabbitListener(queues = RabbitMqConfig.BLOG_PUSH_ACTIVE_QUEUE)
    public void consumeActiveFan(BlogPushMqDTO dto) {
        if (dto == null || dto.getBlogId() == null || dto.getFollowId() == null) {
            log.warn("消费活跃粉丝推送消息失败: dto={}", dto);
            return;
        }
        Long blogId = dto.getBlogId();
        Set<Long> followerIds = dto.getFollowerIds();

        redisBlogPushService.pushToFan(blogId, followerIds);

    }

    @RabbitListener(queues = RabbitMqConfig.BLOG_PUSH_UNACTIVE_QUEUE)
    public void consumeUnActiveFan(BlogPushMqDTO dto) {
        if (dto == null || dto.getBlogId() == null || dto.getFollowId() == null) {
            log.warn("消费不活跃粉丝推送消息失败: dto={}", dto);
            return;
        }

        Long blogId = dto.getBlogId();
        Long followId = dto.getFollowId();

        // 推送到博主的收件箱
        redisBlogPushService.pushToOutBox(followId, blogId);
    }

}
