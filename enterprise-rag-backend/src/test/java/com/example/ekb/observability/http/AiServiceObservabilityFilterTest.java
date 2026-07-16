package com.example.ekb.observability.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import com.example.ekb.common.utils.RequestIdHolder;
import com.example.ekb.observability.metrics.AiObservabilityMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

class AiServiceObservabilityFilterTest {

    private SimpleMeterRegistry meterRegistry;
    private AiServiceObservabilityFilter filter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new AiServiceObservabilityFilter(new AiObservabilityMetrics(meterRegistry));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        meterRegistry.close();
    }

    @Test
    void shouldPropagateCurrentRequestIdAndRecordLowCardinalityMetric() {
        RequestIdHolder.setRequestId("rag-request-1");
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ClientRequest request = ClientRequest.create(
                        HttpMethod.POST,
                        URI.create("http://localhost:8000/api/v1/retrieval/search")
                )
                .build();

        ClientResponse response = filter.filter(request, downstream -> {
            captured.set(downstream);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).block();

        assertThat(response).isNotNull();
        assertThat(captured.get().headers().getFirst(RequestIdHolder.REQUEST_ID_HEADER))
                .isEqualTo("rag-request-1");
        assertThat(meterRegistry.get("ekb.ai.http.duration")
                .tag("operation", "retrieval_search")
                .tag("outcome", "success")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void shouldNotSendEmptyRequestIdHeader() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ClientRequest request = ClientRequest.create(
                        HttpMethod.POST,
                        URI.create("http://localhost:8000/api/v1/rag/generate")
                )
                .build();

        filter.filter(request, downstream -> {
            captured.set(downstream);
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
        }).block();

        assertThat(captured.get().headers().containsKey(RequestIdHolder.REQUEST_ID_HEADER)).isFalse();
        assertThat(meterRegistry.get("ekb.ai.http.duration")
                .tag("operation", "rag_generate")
                .tag("outcome", "failure")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void shouldNotTurnSuccessfulResponseIntoFailureWhenMetricsThrows() {
        AiObservabilityMetrics failingMetrics = failingMetrics();
        AiServiceObservabilityFilter safeFilter = new AiServiceObservabilityFilter(failingMetrics);
        ClientRequest request = ClientRequest.create(
                        HttpMethod.POST,
                        URI.create("http://localhost:8000/api/v1/retrieval/search")
                )
                .build();

        ClientResponse response = safeFilter.filter(
                request,
                downstream -> Mono.just(ClientResponse.create(HttpStatus.OK).build())
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldPreserveOriginalUpstreamFailureWhenMetricsThrows() {
        AiObservabilityMetrics failingMetrics = failingMetrics();
        AiServiceObservabilityFilter safeFilter = new AiServiceObservabilityFilter(failingMetrics);
        ClientRequest request = ClientRequest.create(
                        HttpMethod.POST,
                        URI.create("http://localhost:8000/api/v1/rag/generate")
                )
                .build();
        IllegalStateException upstreamFailure = new IllegalStateException("upstream failed");

        Throwable thrown = catchThrowable(() -> safeFilter.filter(
                request,
                downstream -> Mono.error(upstreamFailure)
        ).block());

        assertThat(thrown).isSameAs(upstreamFailure);
    }

    private AiObservabilityMetrics failingMetrics() {
        AiObservabilityMetrics failingMetrics = mock(
                AiObservabilityMetrics.class,
                withSettings().mockMaker(MockMakers.SUBCLASS)
        );
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(failingMetrics)
                .recordAiHttp(anyString(), anyString(), anyLong());
        return failingMetrics;
    }
}
