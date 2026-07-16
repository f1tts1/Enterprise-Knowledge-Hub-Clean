package com.example.ekb.indexing.queue;

import java.nio.charset.StandardCharsets;

import com.example.ekb.common.utils.RequestIdHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class IndexingQueueProducer {

    private static final Logger log = LoggerFactory.getLogger(IndexingQueueProducer.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final IndexingQueueProperties properties;

    public IndexingQueueProducer(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            IndexingQueueProperties properties
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(IndexingQueueMessage message) {
        String correlationId = RequestIdHolder.forIndexingTask(message.indexingTaskId());
        String previousRequestId = RequestIdHolder.setRequestId(correlationId);
        try {
            Message amqpMessage = MessageBuilder
                    .withBody(toJson(message).getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    // 消息体继续只保存两个业务 ID。correlationId 是观测元数据，
                    // 同一 PENDING attempt 重投时保持稳定，不参与业务状态判断。
                    .setCorrelationId(correlationId)
                    .build();
            rabbitTemplate.send(properties.getExchangeName(), properties.getRoutingKey(), amqpMessage);
            log.info("Published indexing task to RabbitMQ, exchange={}, routingKey={}, queue={}, documentId={}, taskId={}",
                    properties.getExchangeName(),
                    properties.getRoutingKey(),
                    properties.getQueueName(),
                    message.documentId(),
                    message.indexingTaskId());
        } finally {
            RequestIdHolder.restoreRequestId(previousRequestId);
        }
    }

    private String toJson(IndexingQueueMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize indexing queue message", ex);
        }
    }
}
