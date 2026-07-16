package com.example.ekb.observability.metrics;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class AiObservabilityMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    public AiObservabilityMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordAiHttp(String operation, String outcome, long durationNanos) {
        timer("ekb.ai.http.duration", operation, outcome)
                .record(Duration.ofNanos(nonNegative(durationNanos)));
    }

    public void recordIndexingAttempt(String outcome, String failureStage, long durationNanos) {
        Timer.builder("ekb.indexing.attempt.duration")
                .description("Duration of indexing attempts whose terminal state was committed")
                .tag("outcome", safeTag(outcome))
                .tag("failure_stage", safeTag(failureStage))
                .register(meterRegistry)
                .record(Duration.ofNanos(nonNegative(durationNanos)));
    }

    public void recordRetrieval(String outcome, long durationNanos, int hitCount) {
        timer("ekb.retrieval.duration", "search", outcome)
                .record(Duration.ofNanos(nonNegative(durationNanos)));
        DistributionSummary.builder("ekb.retrieval.hits")
                .description("Number of chunks returned after Java permission filtering")
                .tag("outcome", safeTag(outcome))
                .register(meterRegistry)
                .record(Math.max(0, hitCount));
    }

    public void recordRag(String outcome, long totalNanos, long retrievalNanos, long generationNanos) {
        timer("ekb.rag.duration", "ask", outcome)
                .record(Duration.ofNanos(nonNegative(totalNanos)));
        recordRagPhase("retrieval", outcome, retrievalNanos);
        if (generationNanos >= 0) {
            recordRagPhase("generation", outcome, generationNanos);
        }
    }

    public void recordModelCall(String callType, String outcome, long durationMillis) {
        timer("ekb.model.call.duration", callType, outcome)
                .record(Duration.ofMillis(Math.max(0, durationMillis)));
    }

    public void recordLlmTokens(int promptTokens, int completionTokens) {
        counter("prompt").increment(Math.max(0, promptTokens));
        counter("completion").increment(Math.max(0, completionTokens));
    }

    private void recordRagPhase(String phase, String outcome, long durationNanos) {
        Timer.builder("ekb.rag.phase.duration")
                .description("Duration of the retrieval and generation phases in a RAG request")
                .tag("phase", phase)
                .tag("outcome", safeTag(outcome))
                .register(meterRegistry)
                .record(Duration.ofNanos(nonNegative(durationNanos)));
    }

    private Timer timer(String name, String operation, String outcome) {
        return Timer.builder(name)
                .tag("operation", safeTag(operation))
                .tag("outcome", safeTag(outcome))
                .register(meterRegistry);
    }

    private Counter counter(String type) {
        return Counter.builder("ekb.llm.tokens")
                .description("LLM token usage reported by the OpenAI-compatible provider")
                .tag("type", type)
                .register(meterRegistry);
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private long nonNegative(long value) {
        return Math.max(0L, value);
    }
}
