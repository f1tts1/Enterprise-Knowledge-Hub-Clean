package com.example.ekb.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiRagGenerateResponse(
        String answer,
        @JsonProperty("answer_status") String answerStatus,
        @JsonProperty("cited_context_indexes") List<Integer> citedContextIndexes,
        @JsonProperty("no_answer_reason") String noAnswerReason,
        @JsonProperty("llm_provider") String llmProvider,
        @JsonProperty("llm_model") String llmModel,
        @JsonProperty("llm_latency_ms") Long llmLatencyMs,
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("total_tokens") Integer totalTokens
) {
}
