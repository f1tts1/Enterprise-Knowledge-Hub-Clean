package com.example.ekb.document.dto;

import java.time.LocalDateTime;

import com.example.ekb.document.entity.Document;
import com.example.ekb.indexing.entity.IndexingTask;

public record DocumentIndexStatusResponse(
        Long documentId,
        String documentIndexStatus,
        Integer chunkCount,
        String documentErrorMessage,
        Long indexingTaskId,
        String taskStatus,
        Integer retryCount,
        Integer maxRetry,
        String taskErrorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime updatedAt
) {

    public static DocumentIndexStatusResponse from(Document document, IndexingTask task) {
        return new DocumentIndexStatusResponse(
                document.getId(),
                document.getIndexStatus(),
                document.getChunkCount(),
                document.getErrorMessage(),
                task == null ? null : task.getId(),
                task == null ? null : task.getStatus(),
                task == null ? null : task.getRetryCount(),
                task == null ? null : task.getMaxRetry(),
                task == null ? null : task.getErrorMessage(),
                task == null ? null : task.getStartedAt(),
                task == null ? null : task.getFinishedAt(),
                document.getUpdatedAt()
        );
    }
}
