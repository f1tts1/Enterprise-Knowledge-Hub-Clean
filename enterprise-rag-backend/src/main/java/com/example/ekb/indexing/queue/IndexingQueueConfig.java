package com.example.ekb.indexing.queue;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IndexingQueueProperties.class)
public class IndexingQueueConfig {

    @Bean
    public DirectExchange indexingExchange(IndexingQueueProperties properties) {
        return new DirectExchange(properties.getExchangeName(), true, false);
    }

    @Bean
    public DirectExchange indexingDeadLetterExchange(IndexingQueueProperties properties) {
        return new DirectExchange(properties.getDeadLetterExchangeName(), true, false);
    }

    @Bean
    public Queue indexingQueue(IndexingQueueProperties properties) {
        // RabbitMQ 只负责任务投递和消费确认；业务事实仍然在 MySQL。
        // 配置 DLX 是为了让格式错误或消费者自身异常有可观察的落点。
        return QueueBuilder.durable(properties.getQueueName())
                .withArgument("x-dead-letter-exchange", properties.getDeadLetterExchangeName())
                .withArgument("x-dead-letter-routing-key", properties.getDeadLetterRoutingKey())
                .build();
    }

    @Bean
    public Queue indexingDeadLetterQueue(IndexingQueueProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueueName()).build();
    }

    @Bean
    public Binding indexingBinding(
            @Qualifier("indexingQueue") Queue indexingQueue,
            @Qualifier("indexingExchange") DirectExchange indexingExchange,
            IndexingQueueProperties properties
    ) {
        return BindingBuilder.bind(indexingQueue)
                .to(indexingExchange)
                .with(properties.getRoutingKey());
    }

    @Bean
    public Binding indexingDeadLetterBinding(
            @Qualifier("indexingDeadLetterQueue") Queue indexingDeadLetterQueue,
            @Qualifier("indexingDeadLetterExchange") DirectExchange indexingDeadLetterExchange,
            IndexingQueueProperties properties
    ) {
        return BindingBuilder.bind(indexingDeadLetterQueue)
                .to(indexingDeadLetterExchange)
                .with(properties.getDeadLetterRoutingKey());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory indexingRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            IndexingQueueProperties properties
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(Math.max(1, properties.getPrefetchCount()));
        int concurrentConsumers = Math.max(1, properties.getConcurrentConsumers());
        int maxConcurrentConsumers = Math.max(concurrentConsumers, properties.getMaxConcurrentConsumers());
        factory.setConcurrentConsumers(concurrentConsumers);
        factory.setMaxConcurrentConsumers(maxConcurrentConsumers);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
