package com.example.ekb.indexing.queue;

import java.io.IOException;

import com.example.ekb.indexing.service.IndexingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class IndexingQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexingQueueConsumer.class);

    private final ObjectMapper objectMapper;
    private final IndexingService indexingService;

    public IndexingQueueConsumer(ObjectMapper objectMapper, IndexingService indexingService) {
        this.objectMapper = objectMapper;
        this.indexingService = indexingService;
    }

    @RabbitListener(
            queues = "${app.indexing.queue.queue-name:ekb.indexing.tasks}",
            containerFactory = "indexingRabbitListenerContainerFactory"
    )
    public void handle(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            IndexingQueueMessage queueMessage = parseMessage(message);
            indexingService.processIndexingTask(queueMessage.documentId(), queueMessage.indexingTaskId());
            channel.basicAck(deliveryTag, false);
        } catch (IllegalArgumentException ex) {
            // 格式错误的消息无法靠重试恢复，直接进入 DLQ 供人工排查。
            log.warn("Reject malformed indexing RabbitMQ message to DLQ, deliveryTag={}, error={}",
                    deliveryTag, ex.getMessage(), ex);
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException ex) {
            // processIndexingTask 会把 Python 调用失败写入 MySQL 后正常返回。
            // 这里处理的是消费者自身异常、数据库不可用等未能落库的情况，
            // 消息进入 DLQ，同时 PENDING/RUNNING 任务仍可由状态表和超时机制恢复。
            log.warn("Reject failed indexing RabbitMQ message to DLQ, deliveryTag={}, error={}",
                    deliveryTag, ex.getMessage(), ex);
            channel.basicReject(deliveryTag, false);
        }
    }

    private IndexingQueueMessage parseMessage(Message message) {
        try {
            IndexingQueueMessage queueMessage = objectMapper.readValue(
                    message.getBody(),
                    IndexingQueueMessage.class
            );
            if (queueMessage.documentId() == null || queueMessage.indexingTaskId() == null) {
                throw new IllegalArgumentException("RabbitMQ message missing documentId or indexingTaskId");
            }
            return queueMessage;
        } catch (IOException ex) {
            throw new IllegalArgumentException("RabbitMQ message is not valid indexing JSON", ex);
        }
    }
}
