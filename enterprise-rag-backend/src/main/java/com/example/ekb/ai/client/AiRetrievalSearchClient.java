package com.example.ekb.ai.client;

import com.example.ekb.ai.dto.AiRetrievalSearchRequest;
import com.example.ekb.ai.dto.AiRetrievalSearchResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AiRetrievalSearchClient {

    private final WebClient aiServiceWebClient;

    public AiRetrievalSearchClient(WebClient aiServiceWebClient) {
        this.aiServiceWebClient = aiServiceWebClient;
    }

    public AiRetrievalSearchResponse search(AiRetrievalSearchRequest request) {
        // 检索仍然通过服务间 HTTP DTO 调 Python。Java 不直接连接 Qdrant，
        // 这样 embedding 模型、向量库 SDK 和后续 rerank 都留在 AI 服务边界内。
        return aiServiceWebClient.post()
                .uri("/api/v1/retrieval/search")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiRetrievalSearchResponse.class)
                .block();
    }
}
