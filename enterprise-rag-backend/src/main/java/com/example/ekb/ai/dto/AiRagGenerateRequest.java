package com.example.ekb.ai.dto;

import java.util.List;

public record AiRagGenerateRequest(
        String question,
        List<AiRagContextChunk> contexts
) {
}
