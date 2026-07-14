package com.example.ekb.retrieval.dto;

import java.util.List;

import com.example.ekb.ai.dto.AiRetrievalSearchResponse;

public record RetrievalSearchResponse(
        String query,
        Integer topK,
        String vectorStore,
        String vectorCollection,
        List<RetrievalSearchItem> records
) {

    public static RetrievalSearchResponse from(AiRetrievalSearchResponse response) {
        return new RetrievalSearchResponse(
                response.query(),
                response.topK(),
                response.vectorStore(),
                response.vectorCollection(),
                response.records() == null
                        ? List.of()
                        : response.records().stream().map(RetrievalSearchItem::from).toList()
        );
    }
}
