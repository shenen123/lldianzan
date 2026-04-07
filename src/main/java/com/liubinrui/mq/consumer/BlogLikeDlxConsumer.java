package com.liubinrui.mq.consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liubinrui.config.RabbitMqConfig;
import com.liubinrui.model.dto.mq.BlogLikeMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;
import java.io.IOException;

@Component
@Slf4j
public class BlogLikeDlxConsumer {

    @Autowired
    private BlogLikeMqConsumer blogLikeMqConsumer;
    @Autowired
    private ObjectMapper objectMapper;

}
