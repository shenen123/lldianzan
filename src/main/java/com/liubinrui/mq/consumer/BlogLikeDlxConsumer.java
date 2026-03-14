package com.liubinrui.mq.consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.model.dto.mq.BlogLikeMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;
import java.io.IOException;

@Component
@Slf4j
public class BlogLikeDlxConsumer {

    @Resource
    private BlogLikeMqConsumer blogLikeMqConsumer;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 监听点赞死信队列
     * 注意：这里不要加 @Transactional，也不要轻易抛异常，否则死信队列也会无限循环
     */
    @RabbitListener(queues = RabbitMqConfig.BLOG_LIKE_DLX_QUEUE)
    public void handleDeadLetter(Message message, Channel channel) throws InterruptedException {
        String body = new String(message.getBody());
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        log.error("[点赞死信] 捕获到处理失败的消息！内容：{}", body);

        // 在这里可以做：
        // 1. 发送钉钉/邮件报警给管理员
        // 2. 将消息内容保存到 MySQL 的 "error_log" 表，方便后续人工修复或写脚本重放
        //睡眠时间长些，以便能够在控制台看到阻塞的消息
        //Thread.sleep(80000);
        try {
            // 1. 解析 DTO
            BlogLikeMqDTO dto = objectMapper.readValue(body, BlogLikeMqDTO.class);

            // 2. 【核心动作】尝试重放业务逻辑
            // 注意：这里没有 @Transactional，因为死信处理通常希望尽快完成。
            // 如果 processLikeBusiness 内部有 DB 操作且需要事务，建议在该方法内部加，或者在这里手动开启事务
            // 为了简单起见，假设 processLikeBusiness 内部的 DB 操作是原子的，或者由调用方（主消费者）控制事务
            // 但在死信这里，我们直接调用，如果报错就捕获

            blogLikeMqConsumer.processLikeBusiness(dto);

            // 3.手动 ACK，告诉 MQ 这条死信我已经处理了（记录了），可以删除了
            channel.basicAck(deliveryTag, false);
            log.info("✅ [死信恢复] 消息重放成功，数据已补偿！User:{}, Blog:{}", dto.getUserId(), dto.getBlogId());


        } catch (IOException e) {
            log.error("[点赞死信] ACK 失败", e);
            // 如果 ACK 失败，通常只能记录日志，无法再做更多操作
        }
    }
}
