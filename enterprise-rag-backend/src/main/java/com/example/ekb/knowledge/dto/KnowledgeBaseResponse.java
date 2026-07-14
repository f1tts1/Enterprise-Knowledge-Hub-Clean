package com.example.ekb.knowledge.dto;

import java.time.LocalDateTime;

import com.example.ekb.knowledge.entity.KnowledgeBase;

public record KnowledgeBaseResponse(
        Long id,
        String name,
        String description,
        String visibility,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getVisibility(),
                knowledgeBase.getStatus(),
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt()
        );
    }
}
