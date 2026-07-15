package com.example.ekb.document.dto;

import java.time.LocalDateTime;

import com.example.ekb.document.entity.Document;

public record DocumentResponse(
        Long id,
        Long kbId,
        String fileName,
        String contentType,
        Long fileSize,
        String bucket,
        String objectKey,
        String checksumSha256,
        String indexStatus,
        Integer chunkCount,
        String errorMessage,
        Long indexingTaskId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static DocumentResponse from(Document document) {
        return from(document, document.getCurrentIndexingTaskId());
    }

    public static DocumentResponse from(Document document, Long indexingTaskId) {
        return new DocumentResponse(
                document.getId(),
                document.getKbId(),
                document.getFileName(),
                document.getContentType(),
                document.getFileSize(),
                document.getBucket(),
                document.getObjectKey(),
                document.getChecksumSha256(),
                document.getIndexStatus(),
                document.getChunkCount(),
                document.getErrorMessage(),
                indexingTaskId,
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
