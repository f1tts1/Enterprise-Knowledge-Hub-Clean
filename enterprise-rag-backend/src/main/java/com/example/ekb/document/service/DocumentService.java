package com.example.ekb.document.service;

import com.example.ekb.common.response.PageResponse;
import com.example.ekb.document.dto.DocumentIndexStatusResponse;
import com.example.ekb.document.dto.DocumentResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    DocumentResponse upload(Long currentUserId, Long knowledgeBaseId, MultipartFile file);

    PageResponse<DocumentResponse> list(Long currentUserId, Long knowledgeBaseId, long page, long size);

    DocumentResponse getDetail(Long currentUserId, Long documentId);

    DocumentIndexStatusResponse getIndexStatus(Long currentUserId, Long documentId);

    DocumentIndexStatusResponse retryIndex(Long currentUserId, Long documentId);

    void delete(Long currentUserId, Long documentId);
}
