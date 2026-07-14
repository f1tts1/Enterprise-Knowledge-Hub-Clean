package com.example.ekb.indexing.queue;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.constants.IndexingTaskStatus;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.indexing.entity.IndexingTask;
import com.example.ekb.indexing.mapper.IndexingTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PendingIndexingTaskRepublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingIndexingTaskRepublishScheduler.class);

    private static final int NOT_DELETED = 0;

    private final IndexingTaskMapper indexingTaskMapper;
    private final DocumentMapper documentMapper;
    private final IndexingQueueProducer indexingQueueProducer;
    private final IndexingQueueProperties properties;

    public PendingIndexingTaskRepublishScheduler(
            IndexingTaskMapper indexingTaskMapper,
            DocumentMapper documentMapper,
            IndexingQueueProducer indexingQueueProducer,
            IndexingQueueProperties properties
    ) {
        this.indexingTaskMapper = indexingTaskMapper;
        this.documentMapper = documentMapper;
        this.indexingQueueProducer = indexingQueueProducer;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.indexing.queue.republish-interval-ms:30000}")
    public void republishStalePendingTasks() {
        // 这个重投器只兜底“上传事务已提交，但 RabbitMQ 发布失败或应用在
        // afterCommit 后崩溃”的窗口。重复投递由 task=PENDING 条件更新兜底。
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(
                Math.max(1, properties.getRepublishDelaySeconds())
        );
        List<IndexingTask> tasks = indexingTaskMapper.selectList(new LambdaQueryWrapper<IndexingTask>()
                .eq(IndexingTask::getStatus, IndexingTaskStatus.PENDING)
                .lt(IndexingTask::getCreatedAt, threshold)
                .orderByAsc(IndexingTask::getCreatedAt)
                .orderByAsc(IndexingTask::getId)
                .last("LIMIT " + Math.max(1, properties.getRepublishBatchSize())));
        if (tasks.isEmpty()) {
            return;
        }

        for (IndexingTask task : tasks) {
            republishIfDocumentStillWaiting(task);
        }
    }

    private void republishIfDocumentStillWaiting(IndexingTask task) {
        Document document = documentMapper.selectById(task.getDocumentId());
        if (document == null) {
            log.warn("Skip pending indexing task republish because document is missing, taskId={}, documentId={}",
                    task.getId(), task.getDocumentId());
            return;
        }

        if (!isWaitingForIndex(document)) {
            log.info("Skip pending indexing task republish because document is not waiting for index, taskId={}, documentId={}, documentStatus={}, isDeleted={}",
                    task.getId(), task.getDocumentId(), document.getIndexStatus(), document.getIsDeleted());
            return;
        }

        try {
            indexingQueueProducer.publish(new IndexingQueueMessage(task.getDocumentId(), task.getId()));
            log.info("Republished pending indexing task to RabbitMQ, taskId={}, documentId={}",
                    task.getId(), task.getDocumentId());
        } catch (RuntimeException ex) {
            // RabbitMQ 暂时不可用时继续保持 task=PENDING。下一轮再尝试即可，
            // 不因为队列依赖短暂失败而改写 MySQL 业务状态。
            log.warn("Failed to republish pending indexing task to RabbitMQ, taskId={}, documentId={}, error={}",
                    task.getId(), task.getDocumentId(), ex.getMessage(), ex);
        }
    }

    private boolean isWaitingForIndex(Document document) {
        return document.getIsDeleted() != null
                && document.getIsDeleted() == NOT_DELETED
                && DocumentIndexStatus.PENDING_INDEX.equals(document.getIndexStatus());
    }
}
