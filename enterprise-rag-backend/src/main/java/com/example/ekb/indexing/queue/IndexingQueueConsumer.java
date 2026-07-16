package com.example.ekb.indexing.queue;

import java.io.IOException;

import com.example.ekb.common.utils.RequestIdHolder;
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
        IndexingQueueMessage queueMessage;
        try {
            queueMessage = parseMessage(message);
        } catch (IllegalArgumentException ex) {
            // 格式错误时还拿不到可信 taskId，不能伪造 task correlationId。
            log.warn("Reject malformed indexing RabbitMQ message to DLQ, deliveryTag={}, errorType={}",
                    deliveryTag, ex.getClass().getSimpleName());
            channel.basicReject(deliveryTag, false);
            return;
        }

        // 不信任消息属性里可能被外部伪造的 correlationId，始终由已解析 taskId
        // 生成稳定关联 ID。Rabbit listener 线程会复用，finally 必须恢复 MDC。
        String previousRequestId = RequestIdHolder.setRequestId(
                RequestIdHolder.forIndexingTask(queueMessage.indexingTaskId())
        );
        try {
            indexingService.processIndexingTask(queueMessage.documentId(), queueMessage.indexingTaskId());
            channel.basicAck(deliveryTag, false);
        } catch (IllegalArgumentException ex) {
            // task/document 关联错误无法靠重试恢复，直接进入 DLQ 供人工排查。
            log.warn("Reject invalid indexing RabbitMQ message to DLQ, deliveryTag={}, errorType={}",
                    deliveryTag, ex.getClass().getSimpleName());
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException ex) {
            // processIndexingTask 会把 Python 调用失败写入 MySQL 后正常返回。
            // 这里处理的是消费者自身异常、数据库不可用等未能落库的情况，
            // 消息进入 DLQ，同时 PENDING/RUNNING 任务仍可由状态表和超时机制恢复。
            log.warn("Reject failed indexing RabbitMQ message to DLQ, deliveryTag={}, errorType={}",
                    deliveryTag, ex.getClass().getSimpleName());
            channel.basicReject(deliveryTag, false);
        } finally {
            RequestIdHolder.restoreRequestId(previousRequestId);
        }
    }

    private IndexingQueueMessage parseMessage(Message message) {
        try {
            IndexingQueueMessage queueMessage = objectMapper.readValue(
                    message.getBody(),
                    IndexingQueueMessage.class
            );
            if (queueMessage.documentId() == null
                    || queueMessage.documentId() <= 0
                    || queueMessage.indexingTaskId() == null
                    || queueMessage.indexingTaskId() <= 0) {
                throw new IllegalArgumentException("RabbitMQ message has invalid documentId or indexingTaskId");
            }
            return queueMessage;
        } catch (IOException ex) {
            throw new IllegalArgumentException("RabbitMQ message is not valid indexing JSON", ex);
        }
    }
}
