package com.example.ekb.rag.service;

import com.example.ekb.rag.dto.RagAskRequest;
import com.example.ekb.rag.dto.RagAskResponse;

public interface RagService {

    RagAskResponse ask(Long currentUserId, Long knowledgeBaseId, RagAskRequest request);
}
