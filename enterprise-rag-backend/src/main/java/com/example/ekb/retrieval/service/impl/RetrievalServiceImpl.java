package com.example.ekb.retrieval.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ekb.ai.client.AiRetrievalSearchClient;
import com.example.ekb.ai.dto.AiRetrievalSearchItem;
import com.example.ekb.ai.dto.AiRetrievalSearchRequest;
import com.example.ekb.ai.dto.AiRetrievalSearchResponse;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.utils.RequestIdHolder;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.knowledge.access.KnowledgeBaseAccessService;
import com.example.ekb.observability.metrics.AiObservabilityMetrics;
import com.example.ekb.observability.model.ModelCallLogRecord;
import com.example.ekb.observability.model.ModelCallType;
import com.example.ekb.observability.service.ModelCallLogService;
import com.example.ekb.retrieval.dto.RetrievalSearchRequest;
import com.example.ekb.retrieval.dto.RetrievalSearchResponse;
import com.example.ekb.retrieval.service.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalServiceImpl.class);

    private static final int NOT_DELETED = 0;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final int OVER_FETCH_MULTIPLIER = 3;
    private static final String VECTOR_STORE_QDRANT = "qdrant";

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final DocumentMapper documentMapper;
    private final AiRetrievalSearchClient aiRetrievalSearchClient;
    private final ModelCallLogService modelCallLogService;
    private final AiObservabilityMetrics observabilityMetrics;

    public RetrievalServiceImpl(
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            DocumentMapper documentMapper,
            AiRetrievalSearchClient aiRetrievalSearchClient,
            ModelCallLogService modelCallLogService,
            AiObservabilityMetrics observabilityMetrics
    ) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.documentMapper = documentMapper;
        this.aiRetrievalSearchClient = aiRetrievalSearchClient;
        this.modelCallLogService = modelCallLogService;
        this.observabilityMetrics = observabilityMetrics;
    }

    @Override
    public RetrievalSearchResponse search(Long currentUserId, Long knowledgeBaseId, RetrievalSearchRequest request) {
        long startedNanos = System.nanoTime();
        try {
            // Java 是业务权限主边界：先确认当前用户拥有知识库，再允许调用 Python。
            // Python 不理解登录态，只接收 Java 已确认过的 ownerUserId/kbId。
            ensureOwnedKnowledgeBase(currentUserId, knowledgeBaseId);

            String query = request.query().trim();
            Integer topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
            if (!hasIndexedDocuments(currentUserId, knowledgeBaseId)) {
                RetrievalSearchResponse emptyResponse = new RetrievalSearchResponse(
                        query,
                        topK,
                        VECTOR_STORE_QDRANT,
                        null,
                        List.of()
                );
                recordRetrievalOutcome(
                        "no_indexed_documents",
                        startedNanos,
                        currentUserId,
                        knowledgeBaseId,
                        topK,
                        topK,
                        0,
                        null
                );
                return emptyResponse;
            }

            Integer aiTopK = overFetchTopK(topK);
            AiRetrievalSearchResponse response = aiRetrievalSearchClient.search(new AiRetrievalSearchRequest(
                    currentUserId,
                    knowledgeBaseId,
                    query,
                    aiTopK
            ));
            AiRetrievalSearchResponse filtered = filterActiveDocumentHits(
                    currentUserId,
                    knowledgeBaseId,
                    topK,
                    response
            );
            RetrievalSearchResponse result = RetrievalSearchResponse.from(filtered);
            int hitCount = result.records() == null ? 0 : result.records().size();
            recordSuccessfulEmbeddingCall(currentUserId, response);
            recordRetrievalOutcome(
                    "success",
                    startedNanos,
                    currentUserId,
                    knowledgeBaseId,
                    topK,
                    aiTopK,
                    hitCount,
                    response
            );
            return result;
        } catch (RuntimeException ex) {
            recordRetrievalFailure(startedNanos, currentUserId, knowledgeBaseId, ex);
            throw ex;
        }
    }

    private void recordSuccessfulEmbeddingCall(Long currentUserId, AiRetrievalSearchResponse response) {
        try {
            long embeddingLatencyMs = nonNegative(response.embeddingLatencyMs());
            modelCallLogService.record(new ModelCallLogRecord(
                    RequestIdHolder.getRequestId(),
                    currentUserId,
                    response.embeddingProvider(),
                    response.embeddingModel(),
                    ModelCallType.EMBEDDING,
                    0,
                    0,
                    embeddingLatencyMs,
                    true,
                    null
            ));
            observabilityMetrics.recordModelCall(ModelCallType.EMBEDDING, "success", embeddingLatencyMs);
        } catch (RuntimeException ex) {
            log.warn("Failed to record retrieval embedding observability, errorType={}",
                    ex.getClass().getSimpleName());
        }
    }

    private void recordRetrievalOutcome(
            String outcome,
            long startedNanos,
            Long currentUserId,
            Long knowledgeBaseId,
            Integer requestedTopK,
            Integer aiTopK,
            int hitCount,
            AiRetrievalSearchResponse aiResponse
    ) {
        long durationNanos = System.nanoTime() - startedNanos;
        try {
            observabilityMetrics.recordRetrieval(outcome, durationNanos, hitCount);
            log.info("Retrieval completed, outcome={}, userId={}, kbId={}, requestedTopK={}, aiTopK={}, hitCount={}, embeddingLatencyMs={}, vectorStoreLatencyMs={}, aiTotalLatencyMs={}, javaTotalLatencyMs={}",
                    outcome,
                    currentUserId,
                    knowledgeBaseId,
                    requestedTopK,
                    aiTopK,
                    hitCount,
                    aiResponse == null ? null : aiResponse.embeddingLatencyMs(),
                    aiResponse == null ? null : aiResponse.vectorStoreLatencyMs(),
                    aiResponse == null ? null : aiResponse.totalLatencyMs(),
                    nanosToMillis(durationNanos));
        } catch (RuntimeException ex) {
            log.warn("Failed to record retrieval observability, outcome={}, errorType={}",
                    outcome, ex.getClass().getSimpleName());
        }
    }

    private void recordRetrievalFailure(
            long startedNanos,
            Long currentUserId,
            Long knowledgeBaseId,
            RuntimeException failure
    ) {
        long durationNanos = System.nanoTime() - startedNanos;
        try {
            observabilityMetrics.recordRetrieval("failure", durationNanos, 0);
            log.warn("Retrieval failed, userId={}, kbId={}, javaTotalLatencyMs={}, errorType={}",
                    currentUserId,
                    knowledgeBaseId,
                    nanosToMillis(durationNanos),
                    failure.getClass().getSimpleName());
        } catch (RuntimeException observabilityFailure) {
            log.warn("Failed to record retrieval failure metric, errorType={}",
                    observabilityFailure.getClass().getSimpleName());
        }
    }

    private void ensureOwnedKnowledgeBase(Long currentUserId, Long knowledgeBaseId) {
        knowledgeBaseAccessService.requireOwned(currentUserId, knowledgeBaseId);
    }

    private boolean hasIndexedDocuments(Long currentUserId, Long knowledgeBaseId) {
        Long count = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getOwnerUserId, currentUserId)
                .eq(Document::getKbId, knowledgeBaseId)
                .eq(Document::getIsDeleted, NOT_DELETED)
                .eq(Document::getIndexStatus, DocumentIndexStatus.INDEXED));
        return count != null && count > 0;
    }

    private AiRetrievalSearchResponse filterActiveDocumentHits(
            Long currentUserId,
            Long knowledgeBaseId,
            Integer requestedTopK,
            AiRetrievalSearchResponse response
    ) {
        if (response == null || response.records() == null || response.records().isEmpty()) {
            return response == null
                    ? null
                    : new AiRetrievalSearchResponse(
                            response.query(),
                            requestedTopK,
                            response.vectorStore(),
                            response.vectorCollection(),
                            List.of(),
                            response.embeddingProvider(),
                            response.embeddingModel(),
                            response.embeddingLatencyMs(),
                            response.vectorStoreLatencyMs(),
                            response.totalLatencyMs()
                    );
        }

        // Qdrant 写入和 MySQL 状态更新不是同一个事务：
        // Python 可能已经把 vectors 写入 Qdrant，但 Java 还没来得及把 document 标记为 INDEXED。
        // 因此这里不仅过滤 is_deleted=0，还必须要求 index_status 是已完成索引状态，
        // 避免“半完成索引”或历史残留 vectors 被返回给用户。
        //
        // Qdrant 删除也是外部系统清理，可能因为服务不可用或历史数据残留而暂时不完整。
        // Java 作为业务主边界，在返回前再用 MySQL 的 owner/kb/is_deleted/index_status 做一次防御性过滤。
        // 因为过滤会丢弃部分 Qdrant 命中，所以 search() 会向 Python 适度 over-fetch，
        // 这里再截断回用户真正请求的 topK，保证对外契约稳定。
        Set<Long> candidateDocIds = response.records().stream()
                .map(AiRetrievalSearchItem::docId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (candidateDocIds.isEmpty()) {
            return new AiRetrievalSearchResponse(
                    response.query(),
                    requestedTopK,
                    response.vectorStore(),
                    response.vectorCollection(),
                    List.of(),
                    response.embeddingProvider(),
                    response.embeddingModel(),
                    response.embeddingLatencyMs(),
                    response.vectorStoreLatencyMs(),
                    response.totalLatencyMs()
            );
        }

        Set<Long> activeDocIds = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                        .select(Document::getId)
                        .eq(Document::getOwnerUserId, currentUserId)
                        .eq(Document::getKbId, knowledgeBaseId)
                        .eq(Document::getIsDeleted, NOT_DELETED)
                        .eq(Document::getIndexStatus, DocumentIndexStatus.INDEXED)
                        .in(Document::getId, candidateDocIds))
                .stream()
                .map(Document::getId)
                .collect(Collectors.toSet());

        List<AiRetrievalSearchItem> activeRecords = response.records().stream()
                .filter(item -> item.docId() != null && activeDocIds.contains(item.docId()))
                .limit(requestedTopK)
                .toList();

        return new AiRetrievalSearchResponse(
                response.query(),
                requestedTopK,
                response.vectorStore(),
                response.vectorCollection(),
                activeRecords,
                response.embeddingProvider(),
                response.embeddingModel(),
                response.embeddingLatencyMs(),
                response.vectorStoreLatencyMs(),
                response.totalLatencyMs()
        );
    }

    private Integer overFetchTopK(Integer requestedTopK) {
        return Math.min(MAX_TOP_K, requestedTopK * OVER_FETCH_MULTIPLIER);
    }

    private long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private long nanosToMillis(long durationNanos) {
        return Math.max(0L, durationNanos) / 1_000_000L;
    }
}
