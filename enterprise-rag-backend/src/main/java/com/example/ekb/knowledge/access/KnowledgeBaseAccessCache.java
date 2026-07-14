package com.example.ekb.knowledge.access;

import java.util.Optional;

public interface KnowledgeBaseAccessCache {

    Optional<CachedKnowledgeBaseAccess> get(Long knowledgeBaseId);

    void put(CachedKnowledgeBaseAccess knowledgeBase);

    void evict(Long knowledgeBaseId);
}
