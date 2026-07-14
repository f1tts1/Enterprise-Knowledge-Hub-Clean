package com.example.ekb.retrieval.service;

import com.example.ekb.retrieval.dto.RetrievalSearchRequest;
import com.example.ekb.retrieval.dto.RetrievalSearchResponse;

public interface RetrievalService {

    RetrievalSearchResponse search(Long currentUserId, Long knowledgeBaseId, RetrievalSearchRequest request);
}
