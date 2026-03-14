package com.liubinrui.mq.consumer;
import com.liubinrui.config.RabbitMqConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;

import java.io.IOException;

@Component
@Slf4j
public class BlogPushDlxConsumer {

    /**
     * 监听点赞死信队列
     * 注意：这里不要加 @Transactional，也不要轻易抛异常，否则死信队列也会无限循环
     */
    @RabbitListener(queues = RabbitMqConfig.BLOG_PUSH_DLX_QUEUE)
    public void handleDeadLetter(Message message, Channel channel) {
        String body = new String(message.getBody());
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        log.error("[推送死信] 捕获到处理失败的消息！内容：{}", body);

        // 在这里可以做：
        // 1. 发送报警给管理员
        // 2. 将消息内容保存到 MySQL 的 "error_log" 表，方便后续人工修复或写脚本重放

        try {
            // 手动 ACK，告诉 MQ 这条死信我已经处理了（记录了），可以删除了
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("[点赞死信] ACK 失败", e);
            // 如果 ACK 失败，通常只能记录日志，无法再做更多操作
        }
    }
}
