package com.example.ekb.document.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.constants.IndexingFailureStage;
import com.example.ekb.common.constants.IndexingTaskStatus;
import com.example.ekb.common.utils.RequestIdHolder;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.indexing.entity.IndexingTask;
import com.example.ekb.indexing.mapper.IndexingTaskMapper;
import com.example.ekb.observability.metrics.AiObservabilityMetrics;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.slf4j.MDC;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentStatusTimeoutSchedulerTest {

    private static final long DOCUMENT_ID = 101L;
    private static final long TASK_ID = 201L;
    private static final long KB_ID = 301L;
    private static final long OWNER_USER_ID = 401L;

    private DocumentMapper documentMapper;
    private IndexingTaskMapper indexingTaskMapper;
    private TransactionStatus transactionStatus;
    private AiObservabilityMetrics observabilityMetrics;
    private DocumentStatusTimeoutScheduler scheduler;

    @BeforeAll
    static void initializeMybatisPlusLambdaMetadata() {
        MapperBuilderAssistant builderAssistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "document-timeout-test"
        );
        TableInfoHelper.initTableInfo(builderAssistant, Document.class);
        TableInfoHelper.initTableInfo(builderAssistant, IndexingTask.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = subclassMock(DocumentMapper.class);
        indexingTaskMapper = subclassMock(IndexingTaskMapper.class);
        transactionStatus = subclassMock(TransactionStatus.class);
        observabilityMetrics = subclassMock(AiObservabilityMetrics.class);
        scheduler = new DocumentStatusTimeoutScheduler(
                documentMapper,
                indexingTaskMapper,
                immediateTransactionTemplate(transactionStatus),
                observabilityMetrics
        );
        when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldCasTaskAndDocumentWhenCurrentRunningAttemptTimesOut() {
        when(indexingTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(runningTask()));
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(indexingDocument(TASK_ID));
        when(indexingTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(documentMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        AtomicReference<String> requestIdDuringMetric = new AtomicReference<>();
        doAnswer(invocation -> {
            requestIdDuringMetric.set(RequestIdHolder.getRequestId());
            return null;
        }).when(observabilityMetrics).recordIndexingAttempt(any(), any(), anyLong());
        RequestIdHolder.setRequestId("scheduler-request");

        scheduler.markTimedOutDocuments();

        ArgumentCaptor<LambdaUpdateWrapper<IndexingTask>> taskUpdateCaptor = taskUpdateCaptor();
        verify(indexingTaskMapper).update(isNull(), taskUpdateCaptor.capture());
        assertThat(taskUpdateCaptor.getValue().getParamNameValuePairs().values())
                .contains(IndexingTaskStatus.FAILED, IndexingFailureStage.TIMEOUT, true);

        ArgumentCaptor<LambdaUpdateWrapper<Document>> documentUpdateCaptor = documentUpdateCaptor();
        verify(documentMapper).update(isNull(), documentUpdateCaptor.capture());
        LambdaUpdateWrapper<Document> documentUpdate = documentUpdateCaptor.getValue();
        assertThat(documentUpdate.getParamNameValuePairs().values())
                .contains(DocumentIndexStatus.INDEX_FAILED);

        // 这里只回归 MyBatis-Plus wrapper 是否保留 current-attempt fencing；
        // 它不能替代真实 MySQL 上对条件更新、事务回滚和并发竞争的集成测试。
        assertThat(documentUpdate.getSqlSegment()).contains("current_indexing_task_id");
        assertThat(documentUpdate.getParamNameValuePairs().values()).contains(TASK_ID);
        verify(transactionStatus, never()).setRollbackOnly();
        verify(observabilityMetrics).recordIndexingAttempt(
                eq("failure"),
                eq(IndexingFailureStage.TIMEOUT),
                anyLong()
        );
        assertThat(requestIdDuringMetric.get()).isEqualTo("index-task-201");
        assertThat(RequestIdHolder.getRequestId()).isEqualTo("scheduler-request");
    }

    @Test
    void shouldSkipTimeoutTransitionWhenTaskIsNotCurrentAttempt() {
        when(indexingTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(runningTask()));
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(indexingDocument(TASK_ID + 1));

        scheduler.markTimedOutDocuments();

        verify(indexingTaskMapper, never()).update(any(), any());
        verify(documentMapper, never()).update(any(), any());
        verify(transactionStatus, never()).setRollbackOnly();
        verify(observabilityMetrics, never()).recordIndexingAttempt(any(), any(), anyLong());
    }

    @Test
    void shouldMarkTransactionRollbackOnlyWhenDocumentCasLosesRace() {
        when(indexingTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(runningTask()));
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(indexingDocument(TASK_ID));
        when(indexingTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(documentMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        scheduler.markTimedOutDocuments();

        verify(indexingTaskMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(documentMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(transactionStatus).setRollbackOnly();
        verify(observabilityMetrics, never()).recordIndexingAttempt(any(), any(), anyLong());
    }

    private IndexingTask runningTask() {
        IndexingTask task = new IndexingTask();
        task.setId(TASK_ID);
        task.setDocumentId(DOCUMENT_ID);
        task.setKbId(KB_ID);
        task.setOwnerUserId(OWNER_USER_ID);
        task.setStatus(IndexingTaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now().minusHours(1));
        return task;
    }

    private Document indexingDocument(Long currentTaskId) {
        Document document = new Document();
        document.setId(DOCUMENT_ID);
        document.setKbId(KB_ID);
        document.setOwnerUserId(OWNER_USER_ID);
        document.setCurrentIndexingTaskId(currentTaskId);
        document.setIndexStatus(DocumentIndexStatus.INDEXING);
        document.setIsDeleted(0);
        return document;
    }

    private TransactionTemplate immediateTransactionTemplate(TransactionStatus status) {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(status);
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<LambdaUpdateWrapper<IndexingTask>> taskUpdateCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<LambdaUpdateWrapper<Document>> documentUpdateCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    private static <T> T subclassMock(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }
}
