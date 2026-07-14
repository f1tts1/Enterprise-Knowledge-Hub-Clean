package com.example.ekb.knowledge.service;

import com.example.ekb.common.response.PageResponse;
import com.example.ekb.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.ekb.knowledge.dto.KnowledgeBaseResponse;

public interface KnowledgeBaseService {

    KnowledgeBaseResponse create(Long currentUserId, CreateKnowledgeBaseRequest request);

    PageResponse<KnowledgeBaseResponse> list(Long currentUserId, long page, long size);

    KnowledgeBaseResponse getDetail(Long currentUserId, Long knowledgeBaseId);

    void delete(Long currentUserId, Long knowledgeBaseId);
}
