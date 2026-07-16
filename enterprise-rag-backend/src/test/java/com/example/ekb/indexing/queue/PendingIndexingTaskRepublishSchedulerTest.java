package com.example.ekb.indexing.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.constants.IndexingTaskStatus;
import com.example.ekb.common.utils.RequestIdHolder;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.indexing.entity.IndexingTask;
import com.example.ekb.indexing.mapper.IndexingTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.slf4j.MDC;

class PendingIndexingTaskRepublishSchedulerTest {

    private static final long DOCUMENT_ID = 101L;
    private static final long TASK_ID = 201L;
    private static final long KB_ID = 301L;
    private static final long OWNER_USER_ID = 401L;

    private IndexingTaskMapper indexingTaskMapper;

    private DocumentMapper documentMapper;

    private IndexingQueueProducer indexingQueueProducer;

    private PendingIndexingTaskRepublishScheduler scheduler;

    @BeforeAll
    static void initializeMybatisPlusLambdaMetadata() {
        MapperBuilderAssistant builderAssistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "pending-republish-test"
        );
        TableInfoHelper.initTableInfo(builderAssistant, Document.class);
        TableInfoHelper.initTableInfo(builderAssistant, IndexingTask.class);
    }

    @BeforeEach
    void setUp() {
        indexingTaskMapper = subclassMock(IndexingTaskMapper.class);
        documentMapper = subclassMock(DocumentMapper.class);
        indexingQueueProducer = subclassMock(IndexingQueueProducer.class);
        IndexingQueueProperties properties = new IndexingQueueProperties();
        properties.setRepublishDelaySeconds(30);
        properties.setRepublishBatchSize(20);
        scheduler = new PendingIndexingTaskRepublishScheduler(
                indexingTaskMapper,
                documentMapper,
                indexingQueueProducer,
                properties
        );
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldNotPublishWhenAnotherSchedulerAlreadyClaimedTask() {
        IndexingTask task = pendingTask();
        when(indexingTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(waitingDocument());
        when(indexingTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        scheduler.republishStalePendingTasks();

        verify(indexingTaskMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(indexingQueueProducer, never()).publish(any(IndexingQueueMessage.class));
    }

    @Test
    void shouldPublishExactlyOnceAfterClaimingTask() {
        IndexingTask task = pendingTask();
        when(indexingTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(waitingDocument());
        when(indexingTaskMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        AtomicReference<String> requestIdDuringPublish = new AtomicReference<>();
        doAnswer(invocation -> {
            requestIdDuringPublish.set(RequestIdHolder.getRequestId());
            return null;
        }).when(indexingQueueProducer).publish(any(IndexingQueueMessage.class));
        RequestIdHolder.setRequestId("scheduler-request");

        scheduler.republishStalePendingTasks();

        verify(indexingTaskMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(indexingQueueProducer, times(1))
                .publish(new IndexingQueueMessage(DOCUMENT_ID, TASK_ID));
        assertThat(requestIdDuringPublish.get()).isEqualTo("index-task-201");
        assertThat(RequestIdHolder.getRequestId()).isEqualTo("scheduler-request");
    }

    private IndexingTask pendingTask() {
        IndexingTask task = new IndexingTask();
        task.setId(TASK_ID);
        task.setDocumentId(DOCUMENT_ID);
        task.setKbId(KB_ID);
        task.setOwnerUserId(OWNER_USER_ID);
        task.setStatus(IndexingTaskStatus.PENDING);
        task.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        task.setLastPublishAttemptAt(LocalDateTime.now().minusMinutes(1));
        return task;
    }

    private Document waitingDocument() {
        Document document = new Document();
        document.setId(DOCUMENT_ID);
        document.setKbId(KB_ID);
        document.setOwnerUserId(OWNER_USER_ID);
        document.setCurrentIndexingTaskId(TASK_ID);
        document.setIndexStatus(DocumentIndexStatus.PENDING_INDEX);
        document.setIsDeleted(0);
        return document;
    }

    private static <T> T subclassMock(Class<T> type) {
        return org.mockito.Mockito.mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }
}
