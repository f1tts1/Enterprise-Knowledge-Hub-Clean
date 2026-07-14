package com.example.ekb.indexing.service.impl;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.ekb.ai.client.AiDocumentIndexClient;
import com.example.ekb.ai.dto.AiDocumentIndexRequest;
import com.example.ekb.ai.dto.AiDocumentIndexResponse;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.constants.IndexingTaskStatus;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.indexing.entity.IndexingTask;
import com.example.ekb.indexing.mapper.IndexingTaskMapper;
import com.example.ekb.indexing.queue.IndexingQueueMessage;
import com.example.ekb.indexing.queue.IndexingQueueProducer;
import com.example.ekb.indexing.service.IndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class IndexingServiceImpl implements IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingServiceImpl.class);

    // Java 持有业务数据库，所以任务状态由 Java 统一维护。
    // Python 只负责 AI 侧处理和 Qdrant 写入，不能直接修改业务状态。
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final int NOT_DELETED = 0;
    private static final String DOCUMENT_DELETED_BEFORE_INDEXING = "Document was deleted before indexing";

    private final DocumentMapper documentMapper;
    private final IndexingTaskMapper indexingTaskMapper;
    private final AiDocumentIndexClient aiDocumentIndexClient;
    private final IndexingQueueProducer indexingQueueProducer;
    private final TransactionTemplate transactionTemplate;

    public IndexingServiceImpl(
            DocumentMapper documentMapper,
            IndexingTaskMapper indexingTaskMapper,
            AiDocumentIndexClient aiDocumentIndexClient,
            IndexingQueueProducer indexingQueueProducer,
            TransactionTemplate transactionTemplate
    ) {
        this.documentMapper = documentMapper;
        this.indexingTaskMapper = indexingTaskMapper;
        this.aiDocumentIndexClient = aiDocumentIndexClient;
        this.indexingQueueProducer = indexingQueueProducer;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void requestIndexingAfterUpload(Long documentId, Long indexingTaskId) {
        IndexingQueueMessage message = new IndexingQueueMessage(documentId, indexingTaskId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 上传方法还在事务中时，必须等 MySQL 提交成功后再发布 RabbitMQ 消息。
            // 否则消费者可能读到一个数据库里还不存在、甚至即将回滚的 taskId。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishToQueue(message);
                }
            });
            return;
        }
        publishToQueue(message);
    }

    @Override
    public void processIndexingTask(Long documentId, Long indexingTaskId) {
        Document document = documentMapper.selectById(documentId);
        IndexingTask indexingTask = indexingTaskMapper.selectById(indexingTaskId);
        if (document == null || indexingTask == null) {
            log.warn("Skip indexing because document or task is missing, documentId={}, taskId={}",
                    documentId, indexingTaskId);
            return;
        }

        if (!isTaskProcessable(indexingTask)) {
            log.info("Skip indexing because task is already in terminal or non-processable status, documentId={}, taskId={}, taskStatus={}",
                    documentId, indexingTaskId, indexingTask.getStatus());
            return;
        }

        if (isDeleted(document)) {
            markTaskFailed(indexingTask, DOCUMENT_DELETED_BEFORE_INDEXING);
            log.info("Skip indexing because document was already deleted, documentId={}, taskId={}",
                    documentId, indexingTaskId);
            return;
        }

        if (!markRunning(document, indexingTask)) {
            log.info("Skip indexing because task was no longer processable or document was deleted before running update, documentId={}, taskId={}",
                    documentId, indexingTaskId);
            return;
        }

        try {
            // Python 只接收 AI 服务需要的 DTO：对象存储位置、文档 id、
            // 归属元数据和 checksum。完整权限逻辑仍由 Java 掌握。
            AiDocumentIndexResponse response = aiDocumentIndexClient.indexDocument(toAiRequest(document, indexingTask));
            ensureIndexed(response);
            log.info("AI indexing task indexed, documentId={}, taskId={}, aiStatus={}, pageCount={}, charCount={}, chunkCount={}, embeddedChunkCount={}, indexedChunkCount={}, vectorDim={}, vectorStore={}, vectorCollection={}, embeddingProvider={}, embeddingModel={}, message={}",
                    document.getId(),
                    indexingTask.getId(),
                    response.status(),
                    response.pageCount(),
                    response.charCount(),
                    response.chunkCount(),
                    response.embeddedChunkCount(),
                    response.indexedChunkCount(),
                    response.vectorDim(),
                    response.vectorStore(),
                    response.vectorCollection(),
                    response.embeddingProvider(),
                    response.embeddingModel(),
                    response.message());
            markSucceeded(document, indexingTask, response);
        } catch (RuntimeException ex) {
            markFailed(document, indexingTask, ex);
        }
    }

    private void publishToQueue(IndexingQueueMessage message) {
        try {
            indexingQueueProducer.publish(message);
        } catch (RuntimeException ex) {
            // 这里通常发生在 RabbitMQ 没启动、网络断开、exchange/queue 不可用等场景。
            // 上传事务已经提交，MySQL 才是任务事实来源。这里不再把任务标记 FAILED，
            // 否则一次 MQ 瞬时故障就会让“已保存文件”变成“索引失败”。
            // 保持 indexing_task=PENDING，后续手动重试或简单扫描重投即可。
            log.warn("Failed to publish indexing task to RabbitMQ, documentId={}, taskId={}, error={}",
                    message.documentId(), message.indexingTaskId(), ex.getMessage(), ex);
        }
    }

    private boolean markRunning(Document document, IndexingTask indexingTask) {
        Boolean updated = transactionTemplate.execute(status -> {
            // RUNNING/INDEXING 表示 Java 即将把文件交给 Python。
            // 这还不代表 Qdrant 中已经存在向量；向量写入成功后才会进入 INDEXED/SUCCESS。
            //
            // V1 不再引入额外重试中间态：人工重试时把失败任务重新置为 PENDING 即可。
            // 这里用条件更新保证重复 RabbitMQ 消息不会把 SUCCESS/FAILED 任务拉回 RUNNING。
            int taskUpdated = indexingTaskMapper.update(null, new LambdaUpdateWrapper<IndexingTask>()
                    .eq(IndexingTask::getId, indexingTask.getId())
                    .eq(IndexingTask::getStatus, IndexingTaskStatus.PENDING)
                    .set(IndexingTask::getStatus, IndexingTaskStatus.RUNNING)
                    .set(IndexingTask::getStartedAt, LocalDateTime.now())
                    .set(IndexingTask::getFinishedAt, null)
                    .set(IndexingTask::getErrorMessage, null));
            if (taskUpdated == 0) {
                return false;
            }

            // document 更新也在同一个短事务里。若文档已被删除或删除流程已经把
            // is_deleted 置为 1，任务会在同一事务内收口到 FAILED。
            int documentUpdated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .eq(Document::getId, document.getId())
                    .eq(Document::getIsDeleted, NOT_DELETED)
                    .eq(Document::getIndexStatus, DocumentIndexStatus.PENDING_INDEX)
                    .set(Document::getIndexStatus, DocumentIndexStatus.INDEXING)
                    .set(Document::getErrorMessage, null));
            if (documentUpdated == 0) {
                markTaskFailedInCurrentTransaction(indexingTask, DOCUMENT_DELETED_BEFORE_INDEXING);
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(updated);
    }

    private void markFailed(Document document, IndexingTask indexingTask, RuntimeException ex) {
        // task 和 document 都保存错误信息：前端可以直接展示文档级失败，
        // 排查问题时也可以查看任务历史。
        String errorMessage = truncate(StringUtils.hasText(ex.getMessage())
                ? ex.getMessage()
                : ex.getClass().getSimpleName());

        Boolean updated = transactionTemplate.execute(status -> {
            int taskUpdated = markRunningTaskFailedInCurrentTransaction(indexingTask, errorMessage);
            if (taskUpdated == 0) {
                return false;
            }

            // 只允许仍处于 INDEXING 的文档被当前 worker 写成 INDEX_FAILED。
            // 如果超时调度器已经把任务打失败，或者用户已经发起重试并把任务重新置为 PENDING，
            // 旧 worker 的迟到异常不能覆盖新的业务状态。
            documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .eq(Document::getId, document.getId())
                    .eq(Document::getIsDeleted, NOT_DELETED)
                    .eq(Document::getIndexStatus, DocumentIndexStatus.INDEXING)
                    .set(Document::getIndexStatus, DocumentIndexStatus.INDEX_FAILED)
                    .set(Document::getErrorMessage, errorMessage));
            return true;
        });

        if (Boolean.TRUE.equals(updated)) {
            log.warn("AI indexing task failed, documentId={}, taskId={}, error={}",
                    document.getId(), indexingTask.getId(), errorMessage);
            return;
        }
        log.info("Skip marking stale indexing failure because task is no longer RUNNING, documentId={}, taskId={}",
                document.getId(), indexingTask.getId());
    }

    private void markTaskFailed(IndexingTask indexingTask, String errorMessage) {
        transactionTemplate.executeWithoutResult(status ->
                markTaskFailedInCurrentTransaction(indexingTask, errorMessage));
    }

    private void markTaskFailedInCurrentTransaction(IndexingTask indexingTask, String errorMessage) {
        // 失败态只允许从未完成状态进入，不能覆盖 SUCCESS，也不能覆盖其它线程已经写入的 FAILED。
        // 重试不需要额外中间态，后续人工重试直接把 task 重新置为 PENDING。
        indexingTaskMapper.update(null, new LambdaUpdateWrapper<IndexingTask>()
                .eq(IndexingTask::getId, indexingTask.getId())
                .in(IndexingTask::getStatus, IndexingTaskStatus.PENDING, IndexingTaskStatus.RUNNING)
                .set(IndexingTask::getStatus, IndexingTaskStatus.FAILED)
                .set(IndexingTask::getErrorMessage, truncate(errorMessage))
                .set(IndexingTask::getFinishedAt, LocalDateTime.now()));
    }

    private int markRunningTaskFailedInCurrentTransaction(IndexingTask indexingTask, String errorMessage) {
        // 外部 AI 调用返回时，只能更新仍属于本次执行窗口的 RUNNING 任务。
        // 超时后人工重试会把同一 task 重新置为 PENDING；旧 worker 如果迟到返回，
        // 这里必须 no-op，避免把新的重试状态重新打成 FAILED。
        return indexingTaskMapper.update(null, new LambdaUpdateWrapper<IndexingTask>()
                .eq(IndexingTask::getId, indexingTask.getId())
                .eq(IndexingTask::getStatus, IndexingTaskStatus.RUNNING)
                .set(IndexingTask::getStatus, IndexingTaskStatus.FAILED)
                .set(IndexingTask::getErrorMessage, truncate(errorMessage))
                .set(IndexingTask::getFinishedAt, LocalDateTime.now()));
    }

    private void markSucceeded(Document document, IndexingTask indexingTask, AiDocumentIndexResponse response) {
        Boolean updated = transactionTemplate.execute(status -> {
            // 只有 Python 已经把 chunk/vector/payload 写入 Qdrant 后，Java 才能写成功终态。
            // document 和 task 成功状态必须一起提交；任何一个条件更新失败都回滚。
            // Qdrant 不参与分布式事务：如果这里失败，任务会在外层 catch 中标 FAILED，
            // 检索入口仍以 MySQL 的 INDEXED 状态为准，不返回半完成或已删除文档。
            int taskUpdated = indexingTaskMapper.update(null, new LambdaUpdateWrapper<IndexingTask>()
                    .eq(IndexingTask::getId, indexingTask.getId())
                    .eq(IndexingTask::getStatus, IndexingTaskStatus.RUNNING)
                    .set(IndexingTask::getStatus, IndexingTaskStatus.SUCCESS)
                    .set(IndexingTask::getErrorMessage, null)
                    .set(IndexingTask::getFinishedAt, LocalDateTime.now()));
            if (taskUpdated == 0) {
                return false;
            }

            // 迟到的成功结果不能覆盖超时失败或人工重试后的新状态：
            // 只有仍处于 INDEXING 的文档，才属于当前 RUNNING 任务的执行窗口。
            int documentUpdated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .eq(Document::getId, document.getId())
                    .eq(Document::getIsDeleted, NOT_DELETED)
                    .eq(Document::getIndexStatus, DocumentIndexStatus.INDEXING)
                    .set(Document::getIndexStatus, DocumentIndexStatus.INDEXED)
                    .set(Document::getChunkCount, response.chunkCount())
                    .set(Document::getErrorMessage, null));
            if (documentUpdated == 0) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (!Boolean.TRUE.equals(updated)) {
            throw new IllegalStateException("Indexing task status changed before marking success");
        }
    }

    private AiDocumentIndexRequest toAiRequest(Document document, IndexingTask indexingTask) {
        return new AiDocumentIndexRequest(
                indexingTask.getId(),
                document.getId(),
                document.getKbId(),
                document.getOwnerUserId(),
                document.getFileName(),
                document.getContentType(),
                document.getFileSize(),
                document.getBucket(),
                document.getObjectKey(),
                document.getChecksumSha256()
        );
    }

    private void ensureIndexed(AiDocumentIndexResponse response) {
        // V2 的成功标准是 INDEXED：Python 已经完成解析、切分、embedding，
        // 并且 Qdrant upsert 成功。CHUNKED/EMBEDDED 仍可能出现在直接调试
        // Python 接口的场景，但对 Java 的“文档索引任务”来说还不是成功。
        if (response == null || !DocumentIndexStatus.INDEXED.equals(response.status())) {
            throw new IllegalStateException("AI service did not finish Qdrant indexing");
        }
        if (response.chunkCount() == null || response.indexedChunkCount() == null) {
            throw new IllegalStateException("AI service did not return chunk indexing counts");
        }
        if (response.chunkCount() <= 0 || response.indexedChunkCount() <= 0) {
            throw new IllegalStateException("AI service indexed no chunks");
        }
        if (!response.chunkCount().equals(response.indexedChunkCount())) {
            throw new IllegalStateException("AI service indexed chunk count does not match chunk count");
        }
    }

    private String truncate(String message) {
        if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private boolean isDeleted(Document document) {
        return document.getIsDeleted() == null || document.getIsDeleted() != NOT_DELETED;
    }

    private boolean isTaskProcessable(IndexingTask indexingTask) {
        return IndexingTaskStatus.PENDING.equals(indexingTask.getStatus());
    }
}
