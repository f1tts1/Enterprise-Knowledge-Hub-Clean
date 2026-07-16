package com.example.ekb.rag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;

import com.example.ekb.ai.client.AiRagGenerateClient;
import com.example.ekb.ai.dto.AiRagGenerateRequest;
import com.example.ekb.ai.dto.AiRagGenerateResponse;
import com.example.ekb.observability.metrics.AiObservabilityMetrics;
import com.example.ekb.observability.model.ModelCallLogRecord;
import com.example.ekb.observability.service.ModelCallLogService;
import com.example.ekb.rag.dto.RagAskRequest;
import com.example.ekb.rag.dto.RagAskResponse;
import com.example.ekb.retrieval.dto.RetrievalSearchItem;
import com.example.ekb.retrieval.dto.RetrievalSearchResponse;
import com.example.ekb.retrieval.service.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;

class RagServiceImplTest {

    private static final long USER_ID = 11L;
    private static final long KB_ID = 22L;

    private RetrievalService retrievalService;
    private AiRagGenerateClient aiRagGenerateClient;
    private ModelCallLogService modelCallLogService;
    private AiObservabilityMetrics observabilityMetrics;
    private RagServiceImpl ragService;

    @BeforeEach
    void setUp() {
        retrievalService = subclassMock(RetrievalService.class);
        aiRagGenerateClient = subclassMock(AiRagGenerateClient.class);
        modelCallLogService = subclassMock(ModelCallLogService.class);
        observabilityMetrics = subclassMock(AiObservabilityMetrics.class);
        ragService = new RagServiceImpl(
                retrievalService,
                aiRagGenerateClient,
                modelCallLogService,
                observabilityMetrics
        );
    }

    @Test
    void shouldRecordNoContextWithoutCreatingChatCallOrTokenUsage() {
        when(retrievalService.search(any(), any(), any()))
                .thenReturn(new RetrievalSearchResponse(
                        "question",
                        5,
                        "qdrant",
                        "knowledge_chunks",
                        List.of()
                ));

        RagAskResponse response = ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        );

        assertThat(response.answer()).contains("没有可用于回答");
        verifyNoInteractions(aiRagGenerateClient, modelCallLogService);
        verify(observabilityMetrics).recordRag(
                eq("no_context"),
                anyLong(),
                anyLong(),
                eq(-1L)
        );
        verify(observabilityMetrics, never()).recordLlmTokens(anyInt(), anyInt());
    }

    @Test
    void shouldTreatHitsWithoutUsableTextAsNoContext() {
        when(retrievalService.search(any(), any(), any())).thenReturn(retrievalWithText("   "));

        RagAskResponse response = ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        );

        assertThat(response.answer()).contains("没有可用于回答");
        verifyNoInteractions(aiRagGenerateClient, modelCallLogService);
        verify(observabilityMetrics).recordRag(
                eq("no_context"),
                anyLong(),
                anyLong(),
                eq(-1L)
        );
    }

    @Test
    void shouldPersistSuccessfulChatMetadataAndTokenUsage() {
        when(retrievalService.search(any(), any(), any())).thenReturn(retrievalWithChunk());
        when(aiRagGenerateClient.generate(any(AiRagGenerateRequest.class)))
                .thenReturn(new AiRagGenerateResponse(
                        "grounded answer",
                        "openai-compatible",
                        "deepseek-chat",
                        123L,
                        10,
                        4,
                        14
                ));

        RagAskResponse response = ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        );

        assertThat(response.answer()).isEqualTo("grounded answer");
        ArgumentCaptor<ModelCallLogRecord> logCaptor =
                ArgumentCaptor.forClass(ModelCallLogRecord.class);
        verify(modelCallLogService).record(logCaptor.capture());
        assertThat(logCaptor.getValue().callType()).isEqualTo("CHAT");
        assertThat(logCaptor.getValue().promptTokens()).isEqualTo(10);
        assertThat(logCaptor.getValue().completionTokens()).isEqualTo(4);
        assertThat(logCaptor.getValue().latencyMs()).isEqualTo(123L);
        assertThat(logCaptor.getValue().success()).isTrue();
        verify(observabilityMetrics).recordModelCall("CHAT", "success", 123L);
        verify(observabilityMetrics).recordLlmTokens(10, 4);
        verify(observabilityMetrics).recordRag(
                eq("generated"),
                anyLong(),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldRecordFailedChatMetadataWhenGenerationThrows() {
        when(retrievalService.search(any(), any(), any())).thenReturn(retrievalWithChunk());
        when(aiRagGenerateClient.generate(any(AiRagGenerateRequest.class)))
                .thenThrow(new IllegalStateException("provider response must not be logged"));

        assertThatThrownBy(() -> ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        )).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<ModelCallLogRecord> logCaptor =
                ArgumentCaptor.forClass(ModelCallLogRecord.class);
        verify(modelCallLogService).record(logCaptor.capture());
        assertThat(logCaptor.getValue().success()).isFalse();
        assertThat(logCaptor.getValue().errorMessage()).isEqualTo("IllegalStateException");
        verify(observabilityMetrics).recordModelCall(eq("CHAT"), eq("failure"), anyLong());
        verify(observabilityMetrics).recordRag(
                eq("failed"),
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(observabilityMetrics, never()).recordLlmTokens(anyInt(), anyInt());
    }

    @Test
    void shouldCountReportedTokensWhenProviderReturnsNoUsableAnswer() {
        when(retrievalService.search(any(), any(), any())).thenReturn(retrievalWithChunk());
        when(aiRagGenerateClient.generate(any(AiRagGenerateRequest.class)))
                .thenReturn(new AiRagGenerateResponse(
                        "  ",
                        "openai-compatible",
                        "deepseek-chat",
                        50L,
                        8,
                        2,
                        10
                ));

        RagAskResponse response = ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        );

        assertThat(response.answer()).isEqualTo("LLM 未返回可用答案。");
        ArgumentCaptor<ModelCallLogRecord> logCaptor =
                ArgumentCaptor.forClass(ModelCallLogRecord.class);
        verify(modelCallLogService).record(logCaptor.capture());
        assertThat(logCaptor.getValue().success()).isFalse();
        assertThat(logCaptor.getValue().errorMessage()).isEqualTo("EMPTY_RESPONSE");
        verify(observabilityMetrics).recordModelCall("CHAT", "failure", 50L);
        verify(observabilityMetrics).recordLlmTokens(8, 2);
        verify(observabilityMetrics).recordRag(
                eq("failed"),
                anyLong(),
                anyLong(),
                anyLong()
        );
    }

    private RetrievalSearchResponse retrievalWithChunk() {
        return retrievalWithText("retrieved text");
    }

    private RetrievalSearchResponse retrievalWithText(String text) {
        RetrievalSearchItem item = new RetrievalSearchItem(
                "point-1",
                0.91,
                33L,
                "chunk-1",
                "handbook.txt",
                1,
                0,
                0,
                20,
                text
        );
        return new RetrievalSearchResponse(
                "question",
                5,
                "qdrant",
                "knowledge_chunks",
                List.of(item)
        );
    }

    private static <T> T subclassMock(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }
}
