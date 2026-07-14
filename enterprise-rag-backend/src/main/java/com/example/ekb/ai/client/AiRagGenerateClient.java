package com.example.ekb.ai.client;

import com.example.ekb.ai.dto.AiRagGenerateRequest;
import com.example.ekb.ai.dto.AiRagGenerateResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AiRagGenerateClient {

    private final WebClient aiServiceWebClient;

    public AiRagGenerateClient(WebClient aiServiceWebClient) {
        this.aiServiceWebClient = aiServiceWebClient;
    }

    public AiRagGenerateResponse generate(AiRagGenerateRequest request) {
        return aiServiceWebClient.post()
                .uri("/api/v1/rag/generate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiRagGenerateResponse.class)
                .block();
    }
}
