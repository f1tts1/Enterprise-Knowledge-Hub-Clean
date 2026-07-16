package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiRagGenerateResponse(
        String answer,
        @JsonProperty("llm_provider") String llmProvider,
        @JsonProperty("llm_model") String llmModel,
        @JsonProperty("llm_latency_ms") Long llmLatencyMs,
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("total_tokens") Integer totalTokens
) {
}
