package com.example.ekb.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.ekb.ai.client.AiRetrievalSearchClient;
import com.example.ekb.ai.dto.AiRetrievalSearchItem;
import com.example.ekb.ai.dto.AiRetrievalSearchRequest;
import com.example.ekb.ai.dto.AiRetrievalSearchResponse;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.knowledge.access.KnowledgeBaseAccessService;
import com.example.ekb.observability.metrics.AiObservabilityMetrics;
import com.example.ekb.observability.model.ModelCallLogRecord;
import com.example.ekb.observability.service.ModelCallLogService;
import com.example.ekb.retrieval.dto.RetrievalSearchRequest;
import com.example.ekb.retrieval.dto.RetrievalSearchResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;

class RetrievalServiceImplTest {

    private static final long USER_ID = 11L;
    private static final long KB_ID = 22L;
    private static final long DOCUMENT_ID = 33L;

    private KnowledgeBaseAccessService knowledgeBaseAccessService;
    private DocumentMapper documentMapper;
    private AiRetrievalSearchClient aiRetrievalSearchClient;
    private ModelCallLogService modelCallLogService;
    private AiObservabilityMetrics observabilityMetrics;
    private RetrievalServiceImpl retrievalService;

    @BeforeAll
    static void initializeMybatisPlusLambdaMetadata() {
        MapperBuilderAssistant builderAssistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "retrieval-service-test"
        );
        TableInfoHelper.initTableInfo(builderAssistant, Document.class);
    }

    @BeforeEach
    void setUp() {
        knowledgeBaseAccessService = subclassMock(KnowledgeBaseAccessService.class);
        documentMapper = subclassMock(DocumentMapper.class);
        aiRetrievalSearchClient = subclassMock(AiRetrievalSearchClient.class);
        modelCallLogService = subclassMock(ModelCallLogService.class);
        observabilityMetrics = subclassMock(AiObservabilityMetrics.class);
        retrievalService = new RetrievalServiceImpl(
                knowledgeBaseAccessService,
                documentMapper,
                aiRetrievalSearchClient,
                modelCallLogService,
                observabilityMetrics
        );
    }

    @Test
    void shouldRejectUnauthorizedKnowledgeBaseBeforeCallingPython() {
        when(knowledgeBaseAccessService.requireOwned(USER_ID, KB_ID))
                .thenThrow(new IllegalStateException("not owned"));

        assertThatThrownBy(() -> retrievalService.search(
                USER_ID,
                KB_ID,
                new RetrievalSearchRequest("question", 5)
        )).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(aiRetrievalSearchClient, modelCallLogService);
        verify(observabilityMetrics).recordRetrieval(
                org.mockito.ArgumentMatchers.eq("failure"),
                anyLong(),
                org.mockito.ArgumentMatchers.eq(0)
        );
    }

    @Test
    void shouldSkipPythonAndModelLogWhenKnowledgeBaseHasNoIndexedDocuments() {
        when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        RetrievalSearchResponse response = retrievalService.search(
                USER_ID,
                KB_ID,
                new RetrievalSearchRequest("question", null)
        );

        assertThat(response.records()).isEmpty();
        assertThat(response.topK()).isEqualTo(5);
        verifyNoInteractions(aiRetrievalSearchClient, modelCallLogService);
        verify(observabilityMetrics).recordRetrieval(
                org.mockito.ArgumentMatchers.eq("no_indexed_documents"),
                anyLong(),
                org.mockito.ArgumentMatchers.eq(0)
        );
        verify(observabilityMetrics, never()).recordModelCall(any(), any(), anyLong());
    }

    @Test
    void shouldRecordEmbeddingOnlyAfterJavaFiltersActiveDocumentHits() {
        when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(aiRetrievalSearchClient.search(any(AiRetrievalSearchRequest.class)))
                .thenReturn(aiResponse());
        Document activeDocument = new Document();
        activeDocument.setId(DOCUMENT_ID);
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(activeDocument));

        RetrievalSearchResponse response = retrievalService.search(
                USER_ID,
                KB_ID,
                new RetrievalSearchRequest("question", 3)
        );

        assertThat(response.records()).hasSize(1);
        ArgumentCaptor<AiRetrievalSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(AiRetrievalSearchRequest.class);
        verify(aiRetrievalSearchClient).search(requestCaptor.capture());
        assertThat(requestCaptor.getValue().topK()).isEqualTo(9);

        ArgumentCaptor<ModelCallLogRecord> logCaptor =
                ArgumentCaptor.forClass(ModelCallLogRecord.class);
        verify(modelCallLogService).record(logCaptor.capture());
        assertThat(logCaptor.getValue().callType()).isEqualTo("EMBEDDING");
        assertThat(logCaptor.getValue().latencyMs()).isEqualTo(12L);
        assertThat(logCaptor.getValue().success()).isTrue();
        verify(observabilityMetrics).recordModelCall("EMBEDDING", "success", 12L);
        verify(observabilityMetrics).recordRetrieval(
                org.mockito.ArgumentMatchers.eq("success"),
                anyLong(),
                org.mockito.ArgumentMatchers.eq(1)
        );
    }

    private AiRetrievalSearchResponse aiResponse() {
        AiRetrievalSearchItem item = new AiRetrievalSearchItem(
                "point-1",
                0.91,
                DOCUMENT_ID,
                "chunk-1",
                "handbook.txt",
                1,
                0,
                0,
                20,
                "retrieved text"
        );
        return new AiRetrievalSearchResponse(
                "question",
                9,
                "qdrant",
                "knowledge_chunks",
                List.of(item),
                "sentence-transformers",
                "BAAI/bge-small-zh-v1.5",
                12L,
                8L,
                20L
        );
    }

    private static <T> T subclassMock(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }
}
