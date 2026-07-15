package com.example.ekb.indexing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import java.util.Collection;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.ekb.ai.client.AiDocumentIndexClient;
import com.example.ekb.ai.dto.AiDocumentIndexRequest;
import com.example.ekb.ai.dto.AiDocumentIndexResponse;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.constants.IndexingFailureStage;
import com.example.ekb.common.constants.IndexingTaskStatus;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.indexing.entity.IndexingTask;
import com.example.ekb.indexing.mapper.IndexingTaskMapper;
import com.example.ekb.indexing.queue.IndexingQueueProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class IndexingServiceImplTest {

    private static final long DOCUMENT_ID = 101L;
    private static final long TASK_ID = 201L;
    private static final long KB_ID = 301L;
    private static final long OWNER_USER_ID = 401L;

    private DocumentMapper documentMapper;

    private IndexingTaskMapper indexingTaskMapper;

    private AiDocumentIndexClient aiDocumentIndexClient;

    private IndexingQueueProducer indexingQueueProducer;

    private IndexingServiceImpl indexingService;

    @BeforeAll
    static void initializeMybatisPlusLambdaMetadata() {
        MapperBuilderAssistant builderAssistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "indexing-service-test"
        );
        TableInfoHelper.initTableInfo(builderAssistant, Document.class);
        TableInfoHelper.initTableInfo(builderAssistant, IndexingTask.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = subclassMock(DocumentMapper.class);
        indexingTaskMapper = subclassMock(IndexingTaskMapper.class);
        aiDocumentIndexClient = subclassMock(AiDocumentIndexClient.class);
        indexingQueueProducer = subclassMock(IndexingQueueProducer.class);
        indexingService = new IndexingServiceImpl(
                documentMapper,
                indexingTaskMapper,
                aiDocumentIndexClient,
                indexingQueueProducer,
                immediateTransactionTemplate()
        );
    }

    @Test
    void shouldRejectMessageWhoseTaskDoesNotBelongToDocumentBeforeCallingAi() {
        Document document = pendingDocument();
        IndexingTask task = pendingTask();
        task.setOwnerUserId(OWNER_USER_ID + 1);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(indexingTaskMapper.selectById(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> indexingService.processIndexingTask(DOCUMENT_ID, TASK_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verifyNoInteractions(aiDocumentIndexClient);
        verify(indexingTaskMapper, never()).update(any(), any());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void shouldAcknowledgeStaleAttemptWithoutCallingAiOrUpdatingState() {
        Document document = pendingDocument();
        document.setCurrentIndexingTaskId(TASK_ID + 1);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(indexingTaskMapper.selectById(TASK_ID)).thenReturn(pendingTask());

        indexingService.processIndexingTask(DOCUMENT_ID, TASK_ID);

        verifyNoInteractions(aiDocumentIndexClient);
        verify(indexingTaskMapper, never()).update(any(), any());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void shouldMarkCurrentAttemptSuccessfulWhenAiEchoesTaskAndDocumentIds() {
        Document document = pendingDocument();
        IndexingTask task = pendingTask();
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(indexingTaskMapper.selectById(TASK_ID)).thenReturn(task);
        when(indexingTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(documentMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(aiDocumentIndexClient.indexDocument(any(AiDocumentIndexRequest.class)))
                .thenReturn(indexedResponse(TASK_ID, DOCUMENT_ID));

        indexingService.processIndexingTask(DOCUMENT_ID, TASK_ID);

        verify(aiDocumentIndexClient).indexDocument(any(AiDocumentIndexRequest.class));
        verify(indexingTaskMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        ArgumentCaptor<LambdaUpdateWrapper<Document>> documentUpdateCaptor = documentUpdateCaptor();
        verify(documentMapper, times(2)).update(isNull(), documentUpdateCaptor.capture());

        // 这里只回归 MyBatis-Plus wrapper 是否保留 current-attempt fencing；
        // 它不能替代真实 MySQL 上对条件更新、事务提交和并发竞争的集成测试。
        LambdaUpdateWrapper<Document> terminalDocumentUpdate = documentUpdateCaptor.getAllValues().get(1);
        assertThat(terminalDocumentUpdate.getSqlSegment()).contains("current_indexing_task_id");
        assertThat(terminalDocumentUpdate.getParamNameValuePairs().values()).contains(TASK_ID);

        Collection<Object> terminalTaskValues = captureLastTaskUpdateValues();
        assertThat(terminalTaskValues).contains(IndexingTaskStatus.SUCCESS);
    }

    @Test
    void shouldRouteMismatchedAiResponseToFailureTransition() {
        Document document = pendingDocument();
        IndexingTask task = pendingTask();
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(indexingTaskMapper.selectById(TASK_ID)).thenReturn(task);
        when(indexingTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(documentMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(aiDocumentIndexClient.indexDocument(any(AiDocumentIndexRequest.class)))
                .thenReturn(indexedResponse(TASK_ID + 1, DOCUMENT_ID));

        indexingService.processIndexingTask(DOCUMENT_ID, TASK_ID);

        verify(aiDocumentIndexClient).indexDocument(any(AiDocumentIndexRequest.class));
        verify(indexingTaskMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(documentMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));

        Collection<Object> terminalTaskValues = captureLastTaskUpdateValues();
        assertThat(terminalTaskValues)
                .contains(IndexingTaskStatus.FAILED, IndexingFailureStage.AI_PIPELINE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Collection<Object> captureLastTaskUpdateValues() {
        ArgumentCaptor<LambdaUpdateWrapper<IndexingTask>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(indexingTaskMapper, times(2)).update(isNull(), captor.capture());
        return captor.getAllValues().get(1).getParamNameValuePairs().values();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<LambdaUpdateWrapper<Document>> documentUpdateCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    private Document pendingDocument() {
        Document document = new Document();
        document.setId(DOCUMENT_ID);
        document.setKbId(KB_ID);
        document.setOwnerUserId(OWNER_USER_ID);
        document.setFileName("handbook.txt");
        document.setContentType("text/plain");
        document.setFileSize(128L);
        document.setBucket("knowledge-documents");
        document.setObjectKey("documents/handbook.txt");
        document.setChecksumSha256("checksum");
        document.setIndexStatus(DocumentIndexStatus.PENDING_INDEX);
        document.setCurrentIndexingTaskId(TASK_ID);
        document.setIsDeleted(0);
        return document;
    }

    private IndexingTask pendingTask() {
        IndexingTask task = new IndexingTask();
        task.setId(TASK_ID);
        task.setDocumentId(DOCUMENT_ID);
        task.setKbId(KB_ID);
        task.setOwnerUserId(OWNER_USER_ID);
        task.setStatus(IndexingTaskStatus.PENDING);
        return task;
    }

    private AiDocumentIndexResponse indexedResponse(Long taskId, Long documentId) {
        return new AiDocumentIndexResponse(
                taskId,
                documentId,
                DocumentIndexStatus.INDEXED,
                "indexed",
                1,
                128,
                2,
                2,
                2,
                "sentence-transformers",
                "bge-small-zh-v1.5",
                512,
                "qdrant",
                "knowledge_chunks",
                "preview",
                "chunk preview"
        );
    }

    private TransactionTemplate immediateTransactionTemplate() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(subclassMock(TransactionStatus.class));
            }
        };
    }

    private static <T> T subclassMock(Class<T> type) {
        // Mockito 5 默认使用 inline mock maker；部分受限 JVM 禁止动态挂载 agent。
        // 本测试只 mock 普通接口，显式使用 subclass maker 可保持纯单元测试且无需 JVM agent。
        return mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }
}
