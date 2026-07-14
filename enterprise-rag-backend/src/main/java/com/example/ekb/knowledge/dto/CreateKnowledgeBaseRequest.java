package com.example.ekb.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "name is required")
        @Size(max = 128, message = "name length must be at most 128")
        String name,

        @Size(max = 512, message = "description length must be at most 512")
        String description
) {
}
