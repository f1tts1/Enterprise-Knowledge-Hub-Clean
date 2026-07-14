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
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.knowledge.access.KnowledgeBaseAccessService;
import com.example.ekb.retrieval.dto.RetrievalSearchRequest;
import com.example.ekb.retrieval.dto.RetrievalSearchResponse;
import com.example.ekb.retrieval.service.RetrievalService;
import org.springframework.stereotype.Service;

@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final int NOT_DELETED = 0;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final int OVER_FETCH_MULTIPLIER = 3;
    private static final String VECTOR_STORE_QDRANT = "qdrant";

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final DocumentMapper documentMapper;
    private final AiRetrievalSearchClient aiRetrievalSearchClient;

    public RetrievalServiceImpl(
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            DocumentMapper documentMapper,
            AiRetrievalSearchClient aiRetrievalSearchClient
    ) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.documentMapper = documentMapper;
        this.aiRetrievalSearchClient = aiRetrievalSearchClient;
    }

    @Override
    public RetrievalSearchResponse search(Long currentUserId, Long knowledgeBaseId, RetrievalSearchRequest request) {
        // Java 是业务权限主边界：先确认当前用户拥有知识库，再允许调用 Python。
        // Python 不理解登录态，只接收 Java 已确认过的 ownerUserId/kbId。
        ensureOwnedKnowledgeBase(currentUserId, knowledgeBaseId);

        String query = request.query().trim();
        Integer topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
        if (!hasIndexedDocuments(currentUserId, knowledgeBaseId)) {
            return new RetrievalSearchResponse(
                    query,
                    topK,
                    VECTOR_STORE_QDRANT,
                    null,
                    List.of()
            );
        }

        Integer aiTopK = overFetchTopK(topK);
        AiRetrievalSearchResponse response = aiRetrievalSearchClient.search(new AiRetrievalSearchRequest(
                currentUserId,
                knowledgeBaseId,
                query,
                aiTopK
        ));
        return RetrievalSearchResponse.from(filterActiveDocumentHits(currentUserId, knowledgeBaseId, topK, response));
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
                            List.of()
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
                    List.of()
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
                activeRecords
        );
    }

    private Integer overFetchTopK(Integer requestedTopK) {
        return Math.min(MAX_TOP_K, requestedTopK * OVER_FETCH_MULTIPLIER);
    }
}
