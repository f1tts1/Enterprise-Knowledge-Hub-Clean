package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiRagGenerateResponse(
        String answer,
        @JsonProperty("llm_provider") String llmProvider,
        @JsonProperty("llm_model") String llmModel
) {
}
