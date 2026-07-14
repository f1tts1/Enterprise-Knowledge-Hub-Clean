package com.example.ekb.indexing.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.indexing.queue")
public class IndexingQueueProperties {

    private String exchangeName = "ekb.indexing.exchange";
    private String queueName = "ekb.indexing.tasks";
    private String routingKey = "indexing.task";
    private String deadLetterExchangeName = "ekb.indexing.dlx";
    private String deadLetterQueueName = "ekb.indexing.tasks.dlq";
    private String deadLetterRoutingKey = "indexing.task.dead";
    private long republishIntervalMs = 30000;
    private int republishDelaySeconds = 30;
    private int republishBatchSize = 20;
    private int prefetchCount = 1;
    private int concurrentConsumers = 1;
    private int maxConcurrentConsumers = 1;

    public String getExchangeName() {
        return exchangeName;
    }

    public void setExchangeName(String exchangeName) {
        this.exchangeName = exchangeName;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getDeadLetterExchangeName() {
        return deadLetterExchangeName;
    }

    public void setDeadLetterExchangeName(String deadLetterExchangeName) {
        this.deadLetterExchangeName = deadLetterExchangeName;
    }

    public String getDeadLetterQueueName() {
        return deadLetterQueueName;
    }

    public void setDeadLetterQueueName(String deadLetterQueueName) {
        this.deadLetterQueueName = deadLetterQueueName;
    }

    public String getDeadLetterRoutingKey() {
        return deadLetterRoutingKey;
    }

    public void setDeadLetterRoutingKey(String deadLetterRoutingKey) {
        this.deadLetterRoutingKey = deadLetterRoutingKey;
    }

    public long getRepublishIntervalMs() {
        return republishIntervalMs;
    }

    public void setRepublishIntervalMs(long republishIntervalMs) {
        this.republishIntervalMs = republishIntervalMs;
    }

    public int getRepublishDelaySeconds() {
        return republishDelaySeconds;
    }

    public void setRepublishDelaySeconds(int republishDelaySeconds) {
        this.republishDelaySeconds = republishDelaySeconds;
    }

    public int getRepublishBatchSize() {
        return republishBatchSize;
    }

    public void setRepublishBatchSize(int republishBatchSize) {
        this.republishBatchSize = republishBatchSize;
    }

    public int getPrefetchCount() {
        return prefetchCount;
    }

    public void setPrefetchCount(int prefetchCount) {
        this.prefetchCount = prefetchCount;
    }

    public int getConcurrentConsumers() {
        return concurrentConsumers;
    }

    public void setConcurrentConsumers(int concurrentConsumers) {
        this.concurrentConsumers = concurrentConsumers;
    }

    public int getMaxConcurrentConsumers() {
        return maxConcurrentConsumers;
    }

    public void setMaxConcurrentConsumers(int maxConcurrentConsumers) {
        this.maxConcurrentConsumers = maxConcurrentConsumers;
    }
}
