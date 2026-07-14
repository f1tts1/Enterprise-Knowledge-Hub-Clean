package com.example.ekb.rag.dto;

import java.util.List;

import com.example.ekb.retrieval.dto.RetrievalSearchItem;

public record RagAskResponse(
        String question,
        String answer,
        Integer topK,
        String llmProvider,
        String llmModel,
        String vectorStore,
        String vectorCollection,
        List<RagCitation> citations,
        List<RetrievalSearchItem> retrievedChunks
) {
}
