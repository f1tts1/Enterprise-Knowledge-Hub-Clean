package com.example.ekb.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiRetrievalSearchResponse(
        String query,
        @JsonProperty("top_k") Integer topK,
        @JsonProperty("vector_store") String vectorStore,
        @JsonProperty("vector_collection") String vectorCollection,
        List<AiRetrievalSearchItem> records,
        @JsonProperty("embedding_provider") String embeddingProvider,
        @JsonProperty("embedding_model") String embeddingModel,
        @JsonProperty("embedding_latency_ms") Long embeddingLatencyMs,
        @JsonProperty("vector_store_latency_ms") Long vectorStoreLatencyMs,
        @JsonProperty("total_latency_ms") Long totalLatencyMs
) {
}
