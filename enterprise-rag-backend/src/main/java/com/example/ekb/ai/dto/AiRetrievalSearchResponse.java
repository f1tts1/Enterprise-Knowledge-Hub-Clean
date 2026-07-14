package com.example.ekb.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiRetrievalSearchResponse(
        String query,
        @JsonProperty("top_k") Integer topK,
        @JsonProperty("vector_store") String vectorStore,
        @JsonProperty("vector_collection") String vectorCollection,
        List<AiRetrievalSearchItem> records
) {
}
