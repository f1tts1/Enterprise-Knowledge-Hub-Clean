package com.example.ekb.ai.client;

import com.example.ekb.ai.dto.AiDocumentVectorDeleteRequest;
import com.example.ekb.ai.dto.AiDocumentVectorDeleteResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AiDocumentVectorDeleteClient {

    private final WebClient aiServiceWebClient;

    public AiDocumentVectorDeleteClient(WebClient aiServiceWebClient) {
        this.aiServiceWebClient = aiServiceWebClient;
    }

    public AiDocumentVectorDeleteResponse deleteDocumentVectors(AiDocumentVectorDeleteRequest request) {
        // 删除 vectors 仍然通过服务间 HTTP DTO 调 Python。
        // Java 不直接连接 Qdrant，避免把向量库 SDK 和 filter 细节扩散到业务后端。
        return aiServiceWebClient.post()
                .uri("/api/v1/documents/delete-vectors")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiDocumentVectorDeleteResponse.class)
                .block();
    }
}
