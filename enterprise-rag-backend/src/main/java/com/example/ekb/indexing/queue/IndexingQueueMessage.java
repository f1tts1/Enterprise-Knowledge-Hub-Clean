package com.example.ekb.indexing.queue;

/**
 * RabbitMQ 中的索引任务消息。
 *
 * <p>消息体只保存 documentId 和 indexingTaskId。消费者拿到 id 后重新查询
 * MySQL 获取文件位置、owner、kb 和当前状态，避免队列消息和业务数据库漂移。</p>
 */
public record IndexingQueueMessage(
        Long documentId,
        Long indexingTaskId
) {
}
