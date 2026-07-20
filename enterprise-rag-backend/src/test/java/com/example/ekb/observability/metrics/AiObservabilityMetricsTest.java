package com.example.ekb.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AiObservabilityMetricsTest {

    @Test
    void shouldRecordTerminalOutcomesAndLlmTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiObservabilityMetrics metrics = new AiObservabilityMetrics(registry);

        metrics.recordIndexingAttempt("success", "none", 1_000_000L);
        metrics.recordRetrieval("success", 2_000_000L, 3);
        metrics.recordRag("answered", 3_000_000L, 1_000_000L, 2_000_000L);
        metrics.recordModelCall("CHAT", "success", 25L);
        metrics.recordLlmTokens(10, 4);

        assertThat(registry.find("ekb.indexing.attempt.duration")
                .tags("outcome", "success", "failure_stage", "none")
                .timer()
                .count()).isEqualTo(1L);
        assertThat(registry.find("ekb.retrieval.hits")
                .tag("outcome", "success")
                .summary()
                .totalAmount()).isEqualTo(3.0);
        assertThat(registry.find("ekb.rag.phase.duration")
                .tags("phase", "generation", "outcome", "answered")
                .timer()
                .count()).isEqualTo(1L);
        assertThat(registry.find("ekb.llm.tokens")
                .tag("type", "prompt")
                .counter()
                .count()).isEqualTo(10.0);
    }

    @Test
    void shouldExposeOnlyLowCardinalityTagKeys() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiObservabilityMetrics metrics = new AiObservabilityMetrics(registry);
        metrics.recordAiHttp("retrieval_search", "success", 1L);
        metrics.recordIndexingAttempt("failure", "TIMEOUT", 1L);
        metrics.recordRetrieval("success", 1L, 1);
        metrics.recordRag("no_context", 1L, 1L, -1L);
        metrics.recordModelCall("EMBEDDING", "success", 1L);
        metrics.recordLlmTokens(1, 1);

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());

        assertThat(tagKeys).containsOnly(
                "operation",
                "outcome",
                "failure_stage",
                "phase",
                "type"
        );
    }
}
