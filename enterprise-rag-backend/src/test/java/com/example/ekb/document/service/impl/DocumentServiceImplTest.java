package com.example.ekb.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.ekb.ai.client.AiDocumentVectorDeleteClient;
import com.example.ekb.ai.dto.AiDocumentVectorDeleteResponse;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.constants.IndexingTaskStatus;
import com.example.ekb.common.constants.IndexingTaskTriggerType;
import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.document.dto.DocumentIndexStatusResponse;
import com.example.ekb.document.dto.DocumentResponse;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.indexing.entity.IndexingTask;
import com.example.ekb.indexing.mapper.IndexingTaskMapper;
import com.example.ekb.indexing.service.IndexingService;
import com.example.ekb.knowledge.access.KnowledgeBaseAccessService;
import com.example.ekb.storage.StorageService;
import com.example.ekb.storage.StoredObject;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DocumentServiceImplTest {

    private static final long OWNER_USER_ID = 11L;
    private static final long KNOWLEDGE_BASE_ID = 22L;
    private static final long DOCUMENT_ID = 33L;
    private static final long OLD_TASK_ID = 44L;
    private static final long NEW_TASK_ID = 45L;
    private static final String STORED_OBJECT_KEY = "users/11/knowledge-bases/22/documents/stored.txt";

    private DocumentMapper documentMapper;

    private IndexingTaskMapper indexingTaskMapper;

    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    private StorageService storageService;

    private IndexingService indexingService;

    private AiDocumentVectorDeleteClient aiDocumentVectorDeleteClient;

    private DocumentServiceImpl documentService;

    @BeforeAll
    static void initializeMybatisLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "unit-test");
        assistant.setCurrentNamespace(DocumentMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, Document.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = subclassMock(DocumentMapper.class);
        indexingTaskMapper = subclassMock(IndexingTaskMapper.class);
        knowledgeBaseAccessService = subclassMock(KnowledgeBaseAccessService.class);
        storageService = subclassMock(StorageService.class);
        indexingService = subclassMock(IndexingService.class);
        aiDocumentVectorDeleteClient = subclassMock(AiDocumentVectorDeleteClient.class);
        documentService = new DocumentServiceImpl(
                documentMapper,
                indexingTaskMapper,
                knowledgeBaseAccessService,
                storageService,
                indexingService,
                aiDocumentVectorDeleteClient
        );
    }

    @Test
    void retryIndexCreatesNewAttemptWithoutReusingFailedTask() {
        Document failedDocument = document(DocumentIndexStatus.INDEX_FAILED);
        IndexingTask failedTask = indexingTask(IndexingTaskStatus.FAILED, 2, 2, 3);
        when(documentMapper.selectOne(any())).thenReturn(failedDocument);
        when(indexingTaskMapper.selectById(OLD_TASK_ID)).thenReturn(failedTask);
        when(indexingTaskMapper.insert(any(IndexingTask.class))).thenAnswer(invocation -> {
            IndexingTask insertedTask = invocation.getArgument(0);
            insertedTask.setId(NEW_TASK_ID);
            return 1;
        });
        when(documentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        DocumentIndexStatusResponse response = documentService.retryIndex(OWNER_USER_ID, DOCUMENT_ID);

        ArgumentCaptor<IndexingTask> insertedTaskCaptor = ArgumentCaptor.forClass(IndexingTask.class);
        verify(indexingTaskMapper).insert(insertedTaskCaptor.capture());
        IndexingTask insertedTask = insertedTaskCaptor.getValue();
        assertThat(insertedTask.getId()).isEqualTo(NEW_TASK_ID);
        assertThat(insertedTask.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(insertedTask.getKbId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(insertedTask.getOwnerUserId()).isEqualTo(OWNER_USER_ID);
        assertThat(insertedTask.getStatus()).isEqualTo(IndexingTaskStatus.PENDING);
        assertThat(insertedTask.getAttemptNo()).isEqualTo(3);
        assertThat(insertedTask.getTriggerType()).isEqualTo(IndexingTaskTriggerType.MANUAL_RETRY);
        assertThat(insertedTask.getRetryCount()).isEqualTo(3);
        assertThat(insertedTask.getMaxRetry()).isEqualTo(3);

        assertThat(response.indexingTaskId()).isEqualTo(NEW_TASK_ID);
        assertThat(response.documentIndexStatus()).isEqualTo(DocumentIndexStatus.PENDING_INDEX);
        assertThat(response.taskStatus()).isEqualTo(IndexingTaskStatus.PENDING);
        assertThat(response.attemptNo()).isEqualTo(3);
        assertThat(response.retryCount()).isEqualTo(3);
        assertThat(response.triggerType()).isEqualTo(IndexingTaskTriggerType.MANUAL_RETRY);
        assertThat(failedTask.getId()).isEqualTo(OLD_TASK_ID);
        assertThat(failedTask.getStatus()).isEqualTo(IndexingTaskStatus.FAILED);
        assertThat(failedTask.getAttemptNo()).isEqualTo(2);
        assertThat(failedTask.getRetryCount()).isEqualTo(2);

        verify(indexingTaskMapper, never()).update(nullable(IndexingTask.class), any(Wrapper.class));
        verify(indexingService).requestIndexingAfterUpload(DOCUMENT_ID, NEW_TASK_ID);
    }

    @Test
    void uploadRollbackSynchronizationCleansStoredObject() {
        StoredObject storedObject = prepareSuccessfulUpload();
        TransactionSynchronizationManager.initSynchronization();

        try {
            DocumentResponse response = documentService.upload(
                    OWNER_USER_ID,
                    KNOWLEDGE_BASE_ID,
                    textFile()
            );
            assertThat(response.id()).isEqualTo(DOCUMENT_ID);

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(storageService).removeObject(storedObject.objectKey());
        } finally {
            clearTransactionSynchronization();
        }
    }

    @Test
    void uploadUnknownTransactionOutcomeKeepsStoredObject() {
        prepareSuccessfulUpload();
        TransactionSynchronizationManager.initSynchronization();

        try {
            DocumentResponse response = documentService.upload(
                    OWNER_USER_ID,
                    KNOWLEDGE_BASE_ID,
                    textFile()
            );
            assertThat(response.id()).isEqualTo(DOCUMENT_ID);

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

            verify(storageService, never()).removeObject(anyString());
        } finally {
            clearTransactionSynchronization();
        }
    }

    @Test
    void retryIndexRejectsWhenMaximumRetryCountWasReached() {
        Document failedDocument = document(DocumentIndexStatus.INDEX_FAILED);
        IndexingTask exhaustedTask = indexingTask(IndexingTaskStatus.FAILED, 3, 3, 3);
        when(documentMapper.selectOne(any())).thenReturn(failedDocument);
        when(indexingTaskMapper.selectById(OLD_TASK_ID)).thenReturn(exhaustedTask);

        assertThatThrownBy(() -> documentService.retryIndex(OWNER_USER_ID, DOCUMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo("Maximum indexing retries reached");
                });

        verify(indexingTaskMapper, never()).insert(any(IndexingTask.class));
        verify(documentMapper, never()).update(nullable(Document.class), any(Wrapper.class));
        verifyNoInteractions(indexingService);
    }

    @Test
    void retryIndexRedispatchesSamePendingTaskWithoutCreatingAttempt() {
        Document pendingDocument = document(DocumentIndexStatus.PENDING_INDEX);
        IndexingTask pendingTask = indexingTask(IndexingTaskStatus.PENDING, 1, 1, 3);
        when(documentMapper.selectOne(any())).thenReturn(pendingDocument);
        when(indexingTaskMapper.selectById(OLD_TASK_ID)).thenReturn(pendingTask);

        DocumentIndexStatusResponse response = documentService.retryIndex(OWNER_USER_ID, DOCUMENT_ID);

        assertThat(response.indexingTaskId()).isEqualTo(OLD_TASK_ID);
        assertThat(response.documentIndexStatus()).isEqualTo(DocumentIndexStatus.PENDING_INDEX);
        assertThat(response.taskStatus()).isEqualTo(IndexingTaskStatus.PENDING);
        assertThat(response.attemptNo()).isEqualTo(1);
        assertThat(response.retryCount()).isEqualTo(1);
        verify(indexingService).requestIndexingAfterUpload(DOCUMENT_ID, OLD_TASK_ID);
        verify(indexingTaskMapper, never()).insert(any(IndexingTask.class));
        verify(indexingTaskMapper, never()).update(nullable(IndexingTask.class), any(Wrapper.class));
        verify(documentMapper, never()).update(nullable(Document.class), any(Wrapper.class));
    }

    @Test
    void deleteRejectsDeletingDocumentWithoutCallingExternalServices() {
        Document deletingDocument = document(DocumentIndexStatus.DELETING);
        deletingDocument.setIsDeleted(1);
        deletingDocument.setDeleteGeneration(4);
        when(documentMapper.selectOne(any())).thenReturn(deletingDocument);

        assertThatThrownBy(() -> documentService.delete(OWNER_USER_ID, DOCUMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_BUSY);
                    assertThat(ex.getMessage()).isEqualTo("Document indexing or deletion is already in progress");
                });

        verify(documentMapper, never()).update(nullable(Document.class), any(Wrapper.class));
        verifyNoInteractions(aiDocumentVectorDeleteClient, storageService, indexingService, indexingTaskMapper);
    }

    @Test
    void deleteReturnsConflictWhenTerminalGenerationCompareAndSetLoses() {
        Document indexedDocument = document(DocumentIndexStatus.INDEXED);
        when(documentMapper.selectOne(any())).thenReturn(indexedDocument);
        // 首次更新认领 generation 成功；终态 CAS 和随后失败标记都因 generation 已过期而失败。
        when(documentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1, 0, 0);
        when(aiDocumentVectorDeleteClient.deleteDocumentVectors(any())).thenReturn(validDeleteResponse());

        assertThatThrownBy(() -> documentService.delete(OWNER_USER_ID, DOCUMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo("Document deletion attempt is no longer current");
                });

        verify(aiDocumentVectorDeleteClient).deleteDocumentVectors(any());
        verify(storageService).removeObject(indexedDocument.getObjectKey());

        ArgumentCaptor<Wrapper> updateWrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentMapper, times(3)).update(isNull(), updateWrapperCaptor.capture());
        List<Wrapper> updateWrappers = updateWrapperCaptor.getAllValues();
        LambdaUpdateWrapper<?> claimWrapper = lambdaUpdateWrapper(updateWrappers.get(0));
        LambdaUpdateWrapper<?> terminalWrapper = lambdaUpdateWrapper(updateWrappers.get(1));
        LambdaUpdateWrapper<?> failureWrapper = lambdaUpdateWrapper(updateWrappers.get(2));

        // 这里只做 MyBatis wrapper 形状的回归保护，不替代真实 MySQL 上的 CAS 集成测试。
        assertDeletionFence(claimWrapper, 0);
        assertThat(claimWrapper.getSqlSet()).contains("delete_generation");
        assertThat(claimWrapper.getParamNameValuePairs()).containsValue(1);
        assertDeletionFence(terminalWrapper, 1);
        assertDeletionFence(failureWrapper, 1);
    }

    @ParameterizedTest
    @MethodSource("mismatchedDeleteResponses")
    void deleteRejectsMismatchedPythonResponseBeforeDeletingMinio(
            AiDocumentVectorDeleteResponse mismatchedResponse
    ) {
        Document indexedDocument = document(DocumentIndexStatus.INDEXED);
        when(documentMapper.selectOne(any())).thenReturn(indexedDocument);
        when(documentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(aiDocumentVectorDeleteClient.deleteDocumentVectors(any())).thenReturn(mismatchedResponse);

        assertThatThrownBy(() -> documentService.delete(OWNER_USER_ID, DOCUMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE);
                    assertThat(ex.getMessage())
                            .isEqualTo("Failed to delete Qdrant vectors: "
                                    + "AI service returned an invalid document deletion result");
                });

        verify(aiDocumentVectorDeleteClient).deleteDocumentVectors(any());
        verify(storageService, never()).removeObject(any());

        ArgumentCaptor<Wrapper> updateWrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentMapper, times(2)).update(isNull(), updateWrapperCaptor.capture());
        List<Wrapper> updateWrappers = updateWrapperCaptor.getAllValues();
        LambdaUpdateWrapper<?> claimWrapper = lambdaUpdateWrapper(updateWrappers.get(0));
        LambdaUpdateWrapper<?> failureWrapper = lambdaUpdateWrapper(updateWrappers.get(1));

        // 这里只做 MyBatis wrapper 形状的回归保护，不替代真实 MySQL 上的 CAS 集成测试。
        assertDeletionFence(claimWrapper, 0);
        assertThat(claimWrapper.getSqlSet()).contains("delete_generation");
        assertThat(claimWrapper.getParamNameValuePairs()).containsValue(1);
        assertDeletionFence(failureWrapper, 1);
    }

    private static Stream<Arguments> mismatchedDeleteResponses() {
        return Stream.of(
                Arguments.of(new AiDocumentVectorDeleteResponse(
                        DOCUMENT_ID + 1,
                        "DELETED",
                        "deleted",
                        "qdrant",
                        "knowledge_chunks",
                        true
                )),
                Arguments.of(new AiDocumentVectorDeleteResponse(
                        DOCUMENT_ID,
                        "FAILED",
                        "failed",
                        "qdrant",
                        "knowledge_chunks",
                        true
                ))
        );
    }

    private AiDocumentVectorDeleteResponse validDeleteResponse() {
        return new AiDocumentVectorDeleteResponse(
                DOCUMENT_ID,
                "DELETED",
                "deleted",
                "qdrant",
                "knowledge_chunks",
                true
        );
    }

    private StoredObject prepareSuccessfulUpload() {
        MockMultipartFile file = textFile();
        StoredObject storedObject = new StoredObject(
                "documents",
                STORED_OBJECT_KEY,
                file.getSize(),
                file.getContentType()
        );
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(storageService.bucketName()).thenReturn("documents");
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document insertedDocument = invocation.getArgument(0);
            insertedDocument.setId(DOCUMENT_ID);
            return 1;
        });
        when(storageService.putObject(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenReturn(storedObject);
        when(indexingTaskMapper.insert(any(IndexingTask.class))).thenAnswer(invocation -> {
            IndexingTask insertedTask = invocation.getArgument(0);
            insertedTask.setId(OLD_TASK_ID);
            return 1;
        });
        when(documentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        return storedObject;
    }

    private MockMultipartFile textFile() {
        return new MockMultipartFile(
                "file",
                "example.txt",
                "text/plain",
                "enterprise knowledge".getBytes()
        );
    }

    private void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private LambdaUpdateWrapper<?> lambdaUpdateWrapper(Wrapper wrapper) {
        assertThat(wrapper).isInstanceOf(LambdaUpdateWrapper.class);
        return (LambdaUpdateWrapper<?>) wrapper;
    }

    private void assertDeletionFence(LambdaUpdateWrapper<?> wrapper, int generation) {
        assertThat(wrapper.getSqlSegment())
                .contains("delete_generation")
                .contains("index_status");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(generation);
    }

    private Document document(String indexStatus) {
        Document document = new Document();
        document.setId(DOCUMENT_ID);
        document.setKbId(KNOWLEDGE_BASE_ID);
        document.setOwnerUserId(OWNER_USER_ID);
        document.setObjectKey("users/11/knowledge-bases/22/documents/example.txt");
        document.setIndexStatus(indexStatus);
        document.setCurrentIndexingTaskId(OLD_TASK_ID);
        document.setDeleteGeneration(0);
        document.setChunkCount(9);
        document.setIsDeleted(0);
        return document;
    }

    private IndexingTask indexingTask(String status, int attemptNo, int retryCount, int maxRetry) {
        IndexingTask task = new IndexingTask();
        task.setId(OLD_TASK_ID);
        task.setDocumentId(DOCUMENT_ID);
        task.setKbId(KNOWLEDGE_BASE_ID);
        task.setOwnerUserId(OWNER_USER_ID);
        task.setStatus(status);
        task.setAttemptNo(attemptNo);
        task.setTriggerType(IndexingTaskTriggerType.MANUAL_RETRY);
        task.setRetryCount(retryCount);
        task.setMaxRetry(maxRetry);
        return task;
    }

    private static <T> T subclassMock(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }
}
