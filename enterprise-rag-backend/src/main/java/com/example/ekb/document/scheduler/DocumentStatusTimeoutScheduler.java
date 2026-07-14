package com.example.ekb.document.scheduler;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
public class DocumentStatusTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(DocumentStatusTimeoutScheduler.class);

    private static final int NOT_DELETED = 0;
    private static final int DELETED = 1;
    private static final int TIMEOUT_MINUTES = 30;
    private static final int TIMEOUT_BATCH_SIZE = 20;
    private static final String INDEX_TIMEOUT_MESSAGE = "Document indexing timed out; please retry indexing";
    private static final String DELETE_TIMEOUT_MESSAGE = "Document delete timed out; please retry delete";

    private final DocumentMapper documentMapper;
    private final IndexingTaskMapper indexingTaskMapper;

    public DocumentStatusTimeoutScheduler(
            DocumentMapper documentMapper,
            IndexingTaskMapper indexingTaskMapper
    ) {
        this.documentMapper = documentMapper;
        this.indexingTaskMapper = indexingTaskMapper;
    }

    @Scheduled(fixedDelayString = "${app.document.status-timeout-check-interval-ms:60000}")
    public void markTimedOutDocuments() {
        // 这个定时器只做“卡住状态可见化”，不自动补偿外部系统：
        // 1. INDEXING 超时 -> document/task 失败，用户后续人工重试。
        // 2. DELETING 超时 -> DELETE_FAILED，用户再次 DELETE 重试。
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .in(Document::getIndexStatus, List.of(
                        DocumentIndexStatus.INDEXING,
                        DocumentIndexStatus.DELETING
                ))
                .lt(Document::getUpdatedAt, threshold)
                .orderByAsc(Document::getUpdatedAt)
                .orderByAsc(Document::getId)
                .last("LIMIT " + TIMEOUT_BATCH_SIZE));
        if (documents.isEmpty()) {
            return;
        }

        for (Document document : documents) {
            markTimedOutDocument(document, threshold);
        }
    }

    private void markTimedOutDocument(Document document, LocalDateTime threshold) {
        String status = document.getIndexStatus();
        if (DocumentIndexStatus.INDEXING.equals(status)) {
            markIndexingTimedOut(document, threshold);
            return;
        }
        if (DocumentIndexStatus.DELETING.equals(status)) {
            markDeletingTimedOut(document, threshold);
        }
    }

    private void markIndexingTimedOut(Document document, LocalDateTime threshold) {
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .eq(Document::getIsDeleted, NOT_DELETED)
                .eq(Document::getIndexStatus, DocumentIndexStatus.INDEXING)
                .lt(Document::getUpdatedAt, threshold)
                .set(Document::getIndexStatus, DocumentIndexStatus.INDEX_FAILED)
                .set(Document::getErrorMessage, INDEX_TIMEOUT_MESSAGE));
        if (updated == 0) {
            return;
        }

        indexingTaskMapper.update(null, new LambdaUpdateWrapper<IndexingTask>()
                .eq(IndexingTask::getDocumentId, document.getId())
                .eq(IndexingTask::getStatus, IndexingTaskStatus.RUNNING)
                .set(IndexingTask::getStatus, IndexingTaskStatus.FAILED)
                .set(IndexingTask::getErrorMessage, INDEX_TIMEOUT_MESSAGE)
                .set(IndexingTask::getFinishedAt, LocalDateTime.now()));
        log.warn("Marked indexing document timed out, documentId={}", document.getId());
    }

    private void markDeletingTimedOut(Document document, LocalDateTime threshold) {
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, document.getId())
                .eq(Document::getIsDeleted, DELETED)
                .eq(Document::getIndexStatus, DocumentIndexStatus.DELETING)
                .lt(Document::getUpdatedAt, threshold)
                .set(Document::getIndexStatus, DocumentIndexStatus.DELETE_FAILED)
                .set(Document::getErrorMessage, DELETE_TIMEOUT_MESSAGE));
        if (updated > 0) {
            log.warn("Marked deleting document timed out, documentId={}", document.getId());
        }
    }
}
