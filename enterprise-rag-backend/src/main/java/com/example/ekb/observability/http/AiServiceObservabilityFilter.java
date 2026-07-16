package com.example.ekb.observability.http;

import com.example.ekb.common.utils.RequestIdHolder;
import com.example.ekb.observability.metrics.AiObservabilityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

@Component
public class AiServiceObservabilityFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(AiServiceObservabilityFilter.class);

    private final AiObservabilityMetrics metrics;

    public AiServiceObservabilityFilter(AiObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String requestId = RequestIdHolder.getRequestId();
        ClientRequest downstreamRequest = withRequestId(request, requestId);
        String operation = operationFor(downstreamRequest.url().getPath());
        long startedNanos = System.nanoTime();

        return next.exchange(downstreamRequest)
                .doOnSuccess(response -> recordResponse(
                        requestId,
                        operation,
                        downstreamRequest,
                        response,
                        System.nanoTime() - startedNanos
                ))
                .doOnError(error -> recordError(
                        requestId,
                        operation,
                        downstreamRequest,
                        error,
                        System.nanoTime() - startedNanos
                ));
    }

    private ClientRequest withRequestId(ClientRequest request, String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return request;
        }
        return ClientRequest.from(request)
                .headers(headers -> headers.set(RequestIdHolder.REQUEST_ID_HEADER, requestId))
                .build();
    }

    private void recordResponse(
            String requestId,
            String operation,
            ClientRequest request,
            ClientResponse response,
            long durationNanos
    ) {
        try {
            HttpStatusCode status = response == null ? null : response.statusCode();
            String outcome = status != null && status.is2xxSuccessful() ? "success" : "failure";
            metrics.recordAiHttp(operation, outcome, durationNanos);
            log.info("AI service HTTP completed, requestId={}, operation={}, method={}, path={}, status={}, durationMs={}",
                    requestId,
                    operation,
                    request.method(),
                    request.url().getPath(),
                    status == null ? null : status.value(),
                    nanosToMillis(durationNanos));
        } catch (RuntimeException observabilityFailure) {
            // Reactor side-effect 回调抛错会污染原始 signal；观测旁路必须吞掉自身异常。
            log.warn("Failed to record AI service HTTP response observability, requestId={}, operation={}, errorType={}",
                    requestId, operation, observabilityFailure.getClass().getSimpleName());
        }
    }

    private void recordError(
            String requestId,
            String operation,
            ClientRequest request,
            Throwable error,
            long durationNanos
    ) {
        try {
            metrics.recordAiHttp(operation, "failure", durationNanos);
            log.warn("AI service HTTP failed, requestId={}, operation={}, method={}, path={}, durationMs={}, errorType={}",
                    requestId,
                    operation,
                    request.method(),
                    request.url().getPath(),
                    nanosToMillis(durationNanos),
                    error.getClass().getSimpleName());
        } catch (RuntimeException observabilityFailure) {
            // 不能用指标异常覆盖真正的网络/上游异常，调用方必须收到原始 error。
            log.warn("Failed to record AI service HTTP failure observability, requestId={}, operation={}, errorType={}",
                    requestId, operation, observabilityFailure.getClass().getSimpleName());
        }
    }

    private String operationFor(String path) {
        if (path.endsWith("/documents/index")) {
            return "document_index";
        }
        if (path.endsWith("/documents/delete-vectors")) {
            return "document_vector_delete";
        }
        if (path.endsWith("/retrieval/search")) {
            return "retrieval_search";
        }
        if (path.endsWith("/rag/generate")) {
            return "rag_generate";
        }
        return "unknown";
    }

    private long nanosToMillis(long durationNanos) {
        return Math.max(0L, durationNanos) / 1_000_000L;
    }
}
