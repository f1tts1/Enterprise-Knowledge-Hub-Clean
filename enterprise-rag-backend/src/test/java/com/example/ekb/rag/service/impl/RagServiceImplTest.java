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
                observabilityMetrics,
                -1.0
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
        assertThat(response.answerStatus()).isEqualTo("NO_CONTEXT");
        assertThat(response.noAnswer()).isTrue();
        assertThat(response.noAnswerReason()).isEqualTo("NO_RETRIEVED_CONTEXT");
        assertThat(response.citations()).isEmpty();
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
        assertThat(response.answerStatus()).isEqualTo("NO_CONTEXT");
        assertThat(response.noAnswerReason()).isEqualTo("NO_USABLE_CONTEXT");
        assertThat(response.citations()).isEmpty();
        assertThat(response.retrievedChunks()).hasSize(1);
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
                        "grounded answer [片段 1]",
                        "ANSWERED",
                        List.of(1),
                        null,
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

        assertThat(response.answer()).isEqualTo("grounded answer [片段 1]");
        assertThat(response.answerStatus()).isEqualTo("ANSWERED");
        assertThat(response.noAnswer()).isFalse();
        assertThat(response.noAnswerReason()).isNull();
        assertThat(response.citations()).extracting(citation -> citation.index())
                .containsExactly(1);
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
                eq("answered"),
                anyLong(),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldReturnOnlyCitationsActuallyReferencedByTheAnswer() {
        RetrievalSearchItem first = retrievalItem(31L, "chunk-1", "first context", 0.91);
        RetrievalSearchItem second = retrievalItem(32L, "chunk-2", "second context", 0.88);
        RetrievalSearchItem third = retrievalItem(33L, "chunk-3", "third context", 0.85);
        when(retrievalService.search(any(), any(), any()))
                .thenReturn(retrievalWithItems(List.of(first, second, third)));
        when(aiRagGenerateClient.generate(any(AiRagGenerateRequest.class)))
                .thenReturn(answeredResponse("answer [片段 2]", List.of(2, 2)));

        RagAskResponse response = ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 3)
        );

        assertThat(response.retrievedChunks()).hasSize(3);
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).index()).isEqualTo(2);
        assertThat(response.citations().get(0).docId()).isEqualTo(32L);
    }

    @Test
    void shouldMapCitationIndexesAgainstOnlyTheChunksActuallySentToPython() {
        RetrievalSearchItem blank = retrievalItem(30L, "blank", "   ", 0.99);
        RetrievalSearchItem usable = retrievalItem(31L, "usable", "usable context", 0.91);
        when(retrievalService.search(any(), any(), any()))
                .thenReturn(retrievalWithItems(List.of(blank, usable)));
        when(aiRagGenerateClient.generate(any(AiRagGenerateRequest.class)))
                .thenReturn(answeredResponse("answer [片段 1]", List.of(1)));

        RagAskResponse response = ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 2)
        );

        ArgumentCaptor<AiRagGenerateRequest> requestCaptor =
                ArgumentCaptor.forClass(AiRagGenerateRequest.class);
        verify(aiRagGenerateClient).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().contexts()).hasSize(1);
        assertThat(requestCaptor.getValue().contexts().get(0).docId()).isEqualTo(31L);
        assertThat(response.retrievedChunks()).hasSize(2);
        assertThat(response.citations()).extracting(citation -> citation.docId())
                .containsExactly(31L);
    }

    @Test
    void shouldTreatModelReportedInsufficientContextAsSuccessfulModelCall() {
        when(retrievalService.search(any(), any(), any())).thenReturn(retrievalWithChunk());
        when(aiRagGenerateClient.generate(any(AiRagGenerateRequest.class)))
                .thenReturn(new AiRagGenerateResponse(
                        "当前知识库内容不足，无法回答该问题。",
                        "INSUFFICIENT_CONTEXT",
                        List.of(),
                        "MODEL_REPORTED_INSUFFICIENT_CONTEXT",
                        "openai-compatible",
                        "deepseek-chat",
                        60L,
                        8,
                        2,
                        10
                ));

        RagAskResponse response = ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        );

        assertThat(response.answerStatus()).isEqualTo("INSUFFICIENT_CONTEXT");
        assertThat(response.noAnswer()).isTrue();
        assertThat(response.noAnswerReason())
                .isEqualTo("MODEL_REPORTED_INSUFFICIENT_CONTEXT");
        assertThat(response.citations()).isEmpty();
        ArgumentCaptor<ModelCallLogRecord> logCaptor =
                ArgumentCaptor.forClass(ModelCallLogRecord.class);
        verify(modelCallLogService).record(logCaptor.capture());
        assertThat(logCaptor.getValue().success()).isTrue();
        verify(observabilityMetrics).recordModelCall("CHAT", "success", 60L);
        verify(observabilityMetrics).recordRag(
                eq("insufficient_context"),
                anyLong(),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldSkipLlmWhenAllUsableChunksAreBelowConfiguredMinimumRelevance() {
        ragService = new RagServiceImpl(
                retrievalService,
                aiRagGenerateClient,
                modelCallLogService,
                observabilityMetrics,
                0.95
        );
        when(retrievalService.search(any(), any(), any())).thenReturn(retrievalWithChunk());

        RagAskResponse response = ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        );

        assertThat(response.answerStatus()).isEqualTo("INSUFFICIENT_CONTEXT");
        assertThat(response.noAnswer()).isTrue();
        assertThat(response.noAnswerReason()).isEqualTo("LOW_RELEVANCE");
        assertThat(response.citations()).isEmpty();
        assertThat(response.retrievedChunks()).hasSize(1);
        verifyNoInteractions(aiRagGenerateClient, modelCallLogService);
        verify(observabilityMetrics).recordRag(
                eq("insufficient_context"),
                anyLong(),
                anyLong(),
                eq(-1L)
        );
    }

    @Test
    void shouldRejectCitedContextIndexOutsideTheSentContextList() {
        when(retrievalService.search(any(), any(), any())).thenReturn(retrievalWithChunk());
        when(aiRagGenerateClient.generate(any(AiRagGenerateRequest.class)))
                .thenReturn(answeredResponse("invalid [片段 2]", List.of(2)));

        assertThatThrownBy(() -> ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        )).isInstanceOf(com.example.ekb.common.exception.BusinessException.class);

        verify(observabilityMetrics).recordRag(
                eq("failed"),
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
    void shouldRejectProviderResponseWithoutUsableAnswer() {
        when(retrievalService.search(any(), any(), any())).thenReturn(retrievalWithChunk());
        when(aiRagGenerateClient.generate(any(AiRagGenerateRequest.class)))
                .thenReturn(new AiRagGenerateResponse(
                        "  ",
                        "ANSWERED",
                        List.of(1),
                        null,
                        "openai-compatible",
                        "deepseek-chat",
                        50L,
                        8,
                        2,
                        10
                ));

        assertThatThrownBy(() -> ragService.ask(
                USER_ID,
                KB_ID,
                new RagAskRequest("question", 5)
        )).isInstanceOf(com.example.ekb.common.exception.BusinessException.class);
        ArgumentCaptor<ModelCallLogRecord> logCaptor =
                ArgumentCaptor.forClass(ModelCallLogRecord.class);
        verify(modelCallLogService).record(logCaptor.capture());
        assertThat(logCaptor.getValue().success()).isFalse();
        assertThat(logCaptor.getValue().errorMessage()).isEqualTo("BusinessException");
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
        return retrievalWithItems(List.of(retrievalItem(33L, "chunk-1", text, 0.91)));
    }

    private RetrievalSearchItem retrievalItem(
            Long docId,
            String chunkId,
            String text,
            double score
    ) {
        return new RetrievalSearchItem(
                "point-" + docId,
                score,
                docId,
                chunkId,
                "handbook.txt",
                1,
                0,
                0,
                20,
                text
        );
    }

    private RetrievalSearchResponse retrievalWithItems(List<RetrievalSearchItem> items) {
        return new RetrievalSearchResponse(
                "question",
                5,
                "qdrant",
                "knowledge_chunks",
                items
        );
    }

    private AiRagGenerateResponse answeredResponse(String answer, List<Integer> indexes) {
        return new AiRagGenerateResponse(
                answer,
                "ANSWERED",
                indexes,
                null,
                "openai-compatible",
                "deepseek-chat",
                50L,
                8,
                2,
                10
        );
    }

    private static <T> T subclassMock(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }
}
