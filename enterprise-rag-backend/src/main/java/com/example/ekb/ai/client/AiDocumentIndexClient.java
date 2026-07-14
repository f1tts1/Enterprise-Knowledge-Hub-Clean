package com.example.ekb.ai.client;

import com.example.ekb.ai.dto.AiDocumentIndexRequest;
import com.example.ekb.ai.dto.AiDocumentIndexResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AiDocumentIndexClient {

    private final WebClient aiServiceWebClient;

    public AiDocumentIndexClient(WebClient aiServiceWebClient) {
        this.aiServiceWebClient = aiServiceWebClient;
    }

    public AiDocumentIndexResponse indexDocument(AiDocumentIndexRequest request) {
        // 这是服务间 HTTP 调用，不是 Java 直接调用 Python 进程。
        // 用 DTO 约定接口，可以让 Java 专注业务状态，Python 在接口背后演进 AI pipeline。
        // 超时时间统一由 WebClientConfig 的 Reactor Netty responseTimeout 控制，
        // 避免每个客户端方法各自写死不同的超时。
        return aiServiceWebClient.post()
                .uri("/api/v1/documents/index")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiDocumentIndexResponse.class)
                .block();
    }
}
