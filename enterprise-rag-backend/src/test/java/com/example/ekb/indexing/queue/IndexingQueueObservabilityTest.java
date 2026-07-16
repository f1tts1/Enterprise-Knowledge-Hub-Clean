package com.example.ekb.indexing.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.example.ekb.common.utils.RequestIdHolder;
import com.example.ekb.indexing.service.IndexingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class IndexingQueueObservabilityTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void producerShouldUseStableTaskCorrelationAndRestoreCallerMdc() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        IndexingQueueProperties properties = properties();
        IndexingQueueProducer producer = new IndexingQueueProducer(
                rabbitTemplate,
                new ObjectMapper(),
                properties
        );
        RequestIdHolder.setRequestId("upload-request");
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        producer.publish(new IndexingQueueMessage(101L, 202L));

        verify(rabbitTemplate).send(
                eq(properties.getExchangeName()),
                eq(properties.getRoutingKey()),
                messageCaptor.capture()
        );
        assertThat(messageCaptor.getValue().getMessageProperties().getCorrelationId())
                .isEqualTo("index-task-202");
        assertThat(RequestIdHolder.getRequestId()).isEqualTo("upload-request");
    }

    @Test
    void consumerShouldUseStableTaskCorrelationAndClearReusableThreadMdc() throws Exception {
        IndexingService indexingService = mock(IndexingService.class);
        Channel channel = mock(Channel.class);
        IndexingQueueConsumer consumer = new IndexingQueueConsumer(new ObjectMapper(), indexingService);
        AtomicReference<String> requestIdInsideService = new AtomicReference<>();
        doAnswer(invocation -> {
            requestIdInsideService.set(RequestIdHolder.getRequestId());
            return null;
        }).when(indexingService).processIndexingTask(101L, 202L);
        Message message = message(7L, "{\"documentId\":101,\"indexingTaskId\":202}");

        consumer.handle(message, channel);

        assertThat(requestIdInsideService.get()).isEqualTo("index-task-202");
        assertThat(RequestIdHolder.getRequestId()).isNull();
        verify(channel).basicAck(7L, false);
    }

    @Test
    void consumerShouldRestorePreviousMdcEvenWhenProcessingFails() throws Exception {
        IndexingService indexingService = mock(IndexingService.class);
        Channel channel = mock(Channel.class);
        IndexingQueueConsumer consumer = new IndexingQueueConsumer(new ObjectMapper(), indexingService);
        when(channel.isOpen()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("db unavailable"))
                .when(indexingService).processIndexingTask(101L, 202L);
        RequestIdHolder.setRequestId("previous-request");

        consumer.handle(message(9L, "{\"documentId\":101,\"indexingTaskId\":202}"), channel);

        assertThat(RequestIdHolder.getRequestId()).isEqualTo("previous-request");
        verify(channel).basicReject(9L, false);
    }

    @Test
    void consumerShouldRejectMalformedJsonWithoutCallingIndexingService() throws Exception {
        IndexingService indexingService = mock(IndexingService.class);
        Channel channel = mock(Channel.class);
        IndexingQueueConsumer consumer = new IndexingQueueConsumer(new ObjectMapper(), indexingService);

        consumer.handle(message(10L, "not-valid-indexing-json"), channel);

        verify(channel).basicReject(10L, false);
        verifyNoInteractions(indexingService);
        assertThat(RequestIdHolder.getRequestId()).isNull();
    }

    private Message message(long deliveryTag, String json) {
        return MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(deliveryTag)
                .build();
    }

    private IndexingQueueProperties properties() {
        IndexingQueueProperties properties = new IndexingQueueProperties();
        properties.setExchangeName("ekb.indexing.exchange");
        properties.setQueueName("ekb.indexing.tasks");
        properties.setRoutingKey("indexing.task");
        return properties;
    }
}
