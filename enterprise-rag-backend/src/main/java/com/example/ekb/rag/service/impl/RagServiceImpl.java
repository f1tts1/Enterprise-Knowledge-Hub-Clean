package com.example.ekb.rag.service.impl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.ekb.ai.client.AiRagGenerateClient;
import com.example.ekb.ai.dto.AiRagContextChunk;
import com.example.ekb.ai.dto.AiRagGenerateRequest;
import com.example.ekb.ai.dto.AiRagGenerateResponse;
import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.common.utils.RequestIdHolder;
import com.example.ekb.observability.metrics.AiObservabilityMetrics;
import com.example.ekb.observability.model.ModelCallLogRecord;
import com.example.ekb.observability.model.ModelCallType;
import com.example.ekb.observability.service.ModelCallLogService;
import com.example.ekb.rag.dto.RagAskRequest;
import com.example.ekb.rag.dto.RagAskResponse;
import com.example.ekb.rag.dto.RagCitation;
import com.example.ekb.rag.service.RagService;
import com.example.ekb.retrieval.dto.RetrievalSearchItem;
import com.example.ekb.retrieval.dto.RetrievalSearchRequest;
import com.example.ekb.retrieval.dto.RetrievalSearchResponse;
import com.example.ekb.retrieval.service.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RagServiceImpl implements RagService {

    private static final Logger log = LoggerFactory.getLogger(RagServiceImpl.class);

    private static final int DEFAULT_TOP_K = 5;
    private static final String ANSWERED = "ANSWERED";
    private static final String NO_CONTEXT = "NO_CONTEXT";
    private static final String INSUFFICIENT_CONTEXT = "INSUFFICIENT_CONTEXT";
    private static final String NO_RETRIEVED_CONTEXT = "NO_RETRIEVED_CONTEXT";
    private static final String NO_USABLE_CONTEXT = "NO_USABLE_CONTEXT";
    private static final String LOW_RELEVANCE = "LOW_RELEVANCE";
    private static final String NO_CONTEXT_ANSWER = "当前知识库没有可用于回答该问题的已索引内容。";
    private static final String LOW_RELEVANCE_ANSWER = "当前知识库检索结果与问题的相关性不足，无法回答。";

    private final RetrievalService retrievalService;
    private final AiRagGenerateClient aiRagGenerateClient;
    private final ModelCallLogService modelCallLogService;
    private final AiObservabilityMetrics observabilityMetrics;
    private final double minimumRelevanceScore;

    public RagServiceImpl(
            RetrievalService retrievalService,
            AiRagGenerateClient aiRagGenerateClient,
            ModelCallLogService modelCallLogService,
            AiObservabilityMetrics observabilityMetrics,
            @Value("${app.rag.minimum-relevance-score:-1}") double minimumRelevanceScore
    ) {
        this.retrievalService = retrievalService;
        this.aiRagGenerateClient = aiRagGenerateClient;
        this.modelCallLogService = modelCallLogService;
        this.observabilityMetrics = observabilityMetrics;
        this.minimumRelevanceScore = minimumRelevanceScore;
    }

    @Override
    public RagAskResponse ask(Long currentUserId, Long knowledgeBaseId, RagAskRequest request) {
        long totalStartedNanos = System.nanoTime();
        long retrievalStartedNanos = System.nanoTime();
        long retrievalNanos = -1L;
        long generationStartedNanos = -1L;
        long generationNanos = -1L;
        boolean generationAttempted = false;
        AiRagGenerateResponse generateResponse = null;

        try {
            String question = request.question().trim();
            Integer topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
            RetrievalSearchResponse retrievalResponse = retrievalService.search(
                    currentUserId,
                    knowledgeBaseId,
                    new RetrievalSearchRequest(question, topK)
            );
            retrievalNanos = System.nanoTime() - retrievalStartedNanos;

            List<RetrievalSearchItem> chunks = retrievalResponse.records() == null
                    ? List.of()
                    : retrievalResponse.records();
            if (chunks.isEmpty()) {
                recordRagOutcome(
                        "no_context",
                        totalStartedNanos,
                        retrievalNanos,
                        -1L,
                        currentUserId,
                        knowledgeBaseId,
                        0,
                        null
                );
                return noAnswerResponse(
                        question,
                        topK,
                        retrievalResponse,
                        chunks,
                        NO_CONTEXT,
                        NO_RETRIEVED_CONTEXT,
                        NO_CONTEXT_ANSWER
                );
            }

            List<RetrievalSearchItem> usableChunks = chunks.stream()
                    .filter(item -> StringUtils.hasText(item.text()))
                    .toList();
            if (usableChunks.isEmpty()) {
                recordRagOutcome(
                        "no_context",
                        totalStartedNanos,
                        retrievalNanos,
                        -1L,
                        currentUserId,
                        knowledgeBaseId,
                        0,
                        null
                );
                return noAnswerResponse(
                        question,
                        topK,
                        retrievalResponse,
                        chunks,
                        NO_CONTEXT,
                        NO_USABLE_CONTEXT,
                        NO_CONTEXT_ANSWER
                );
            }

            List<RetrievalSearchItem> generationChunks = filterByMinimumRelevance(usableChunks);
            if (generationChunks.isEmpty()) {
                recordRagOutcome(
                        "insufficient_context",
                        totalStartedNanos,
                        retrievalNanos,
                        -1L,
                        currentUserId,
                        knowledgeBaseId,
                        0,
                        null
                );
                return noAnswerResponse(
                        question,
                        topK,
                        retrievalResponse,
                        chunks,
                        INSUFFICIENT_CONTEXT,
                        LOW_RELEVANCE,
                        LOW_RELEVANCE_ANSWER
                );
            }

            List<AiRagContextChunk> contexts = toAiContexts(generationChunks);
            generationAttempted = true;
            generationStartedNanos = System.nanoTime();
            generateResponse = aiRagGenerateClient.generate(new AiRagGenerateRequest(
                    question,
                    contexts
            ));
            generationNanos = System.nanoTime() - generationStartedNanos;

            ValidatedGeneration generation = validateGenerationResponse(
                    generateResponse,
                    generationChunks
            );
            recordSuccessfulChatCall(currentUserId, generateResponse, generationNanos);
            recordRagOutcome(
                    generation.outcome(),
                    totalStartedNanos,
                    retrievalNanos,
                    generationNanos,
                    currentUserId,
                    knowledgeBaseId,
                    contexts.size(),
                    generateResponse
            );
            return new RagAskResponse(
                    question,
                    generation.answer(),
                    generation.answerStatus(),
                    generation.noAnswer(),
                    generation.noAnswerReason(),
                    topK,
                    generateResponse.llmProvider(),
                    generateResponse.llmModel(),
                    retrievalResponse.vectorStore(),
                    retrievalResponse.vectorCollection(),
                    generation.citations(),
                    chunks
            );
        } catch (RuntimeException ex) {
            if (retrievalNanos < 0) {
                retrievalNanos = System.nanoTime() - retrievalStartedNanos;
            }
            if (generationAttempted) {
                generationNanos = generationNanos >= 0
                        ? generationNanos
                        : System.nanoTime() - generationStartedNanos;
                recordFailedChatCall(
                        currentUserId,
                        generateResponse,
                        generationNanos,
                        ex.getClass().getSimpleName()
                );
            }
            recordRagFailure(
                    totalStartedNanos,
                    retrievalNanos,
                    generationNanos,
                    currentUserId,
                    knowledgeBaseId,
                    ex
            );
            throw ex;
        }
    }

    private RagAskResponse noAnswerResponse(
            String question,
            Integer topK,
            RetrievalSearchResponse retrievalResponse,
            List<RetrievalSearchItem> chunks,
            String answerStatus,
            String noAnswerReason,
            String answer
    ) {
        return new RagAskResponse(
                question,
                answer,
                answerStatus,
                true,
                noAnswerReason,
                topK,
                null,
                null,
                retrievalResponse.vectorStore(),
                retrievalResponse.vectorCollection(),
                List.of(),
                chunks
        );
    }

    private void recordSuccessfulChatCall(
            Long currentUserId,
            AiRagGenerateResponse response,
            long generationNanos
    ) {
        try {
            long latencyMs = response.llmLatencyMs() == null
                    ? nanosToMillis(generationNanos)
                    : Math.max(0L, response.llmLatencyMs());
            modelCallLogService.record(toChatLogRecord(
                    currentUserId,
                    response,
                    latencyMs,
                    true,
                    null
            ));
            observabilityMetrics.recordModelCall(ModelCallType.CHAT, "success", latencyMs);
            if (response.promptTokens() != null || response.completionTokens() != null) {
                observabilityMetrics.recordLlmTokens(
                        nonNegative(response.promptTokens()),
                        nonNegative(response.completionTokens())
                );
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to record successful LLM observability, errorType={}",
                    ex.getClass().getSimpleName());
        }
    }

    private void recordFailedChatCall(
            Long currentUserId,
            AiRagGenerateResponse response,
            long generationNanos,
            String errorType
    ) {
        try {
            long latencyMs = response != null && response.llmLatencyMs() != null
                    ? Math.max(0L, response.llmLatencyMs())
                    : nanosToMillis(generationNanos);
            modelCallLogService.record(toChatLogRecord(
                    currentUserId,
                    response,
                    latencyMs,
                    false,
                    errorType
            ));
            observabilityMetrics.recordModelCall(ModelCallType.CHAT, "failure", latencyMs);
            if (response != null
                    && (response.promptTokens() != null || response.completionTokens() != null)) {
                // 即使 provider 返回了空答案，只要 usage 存在，这些 token 也已经真实消耗。
                observabilityMetrics.recordLlmTokens(
                        nonNegative(response.promptTokens()),
                        nonNegative(response.completionTokens())
                );
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to record failed LLM observability, errorType={}",
                    ex.getClass().getSimpleName());
        }
    }

    private ModelCallLogRecord toChatLogRecord(
            Long currentUserId,
            AiRagGenerateResponse response,
            long latencyMs,
            boolean success,
            String errorType
    ) {
        return new ModelCallLogRecord(
                RequestIdHolder.getRequestId(),
                currentUserId,
                response == null ? null : response.llmProvider(),
                response == null ? null : response.llmModel(),
                ModelCallType.CHAT,
                response == null ? null : response.promptTokens(),
                response == null ? null : response.completionTokens(),
                latencyMs,
                success,
                errorType
        );
    }

    private void recordRagOutcome(
            String outcome,
            long totalStartedNanos,
            long retrievalNanos,
            long generationNanos,
            Long currentUserId,
            Long knowledgeBaseId,
            int contextCount,
            AiRagGenerateResponse response
    ) {
        long totalNanos = System.nanoTime() - totalStartedNanos;
        try {
            observabilityMetrics.recordRag(outcome, totalNanos, retrievalNanos, generationNanos);
            log.info("RAG completed, outcome={}, userId={}, kbId={}, contextCount={}, retrievalLatencyMs={}, generationLatencyMs={}, totalLatencyMs={}, provider={}, model={}, promptTokens={}, completionTokens={}, totalTokens={}",
                    outcome,
                    currentUserId,
                    knowledgeBaseId,
                    contextCount,
                    nanosToMillis(retrievalNanos),
                    generationNanos < 0 ? null : nanosToMillis(generationNanos),
                    nanosToMillis(totalNanos),
                    response == null ? null : response.llmProvider(),
                    response == null ? null : response.llmModel(),
                    response == null ? null : response.promptTokens(),
                    response == null ? null : response.completionTokens(),
                    response == null ? null : response.totalTokens());
        } catch (RuntimeException ex) {
            log.warn("Failed to record RAG observability, outcome={}, errorType={}",
                    outcome, ex.getClass().getSimpleName());
        }
    }

    private void recordRagFailure(
            long totalStartedNanos,
            long retrievalNanos,
            long generationNanos,
            Long currentUserId,
            Long knowledgeBaseId,
            RuntimeException failure
    ) {
        long totalNanos = System.nanoTime() - totalStartedNanos;
        try {
            observabilityMetrics.recordRag("failed", totalNanos, retrievalNanos, generationNanos);
            log.warn("RAG failed, userId={}, kbId={}, retrievalLatencyMs={}, generationLatencyMs={}, totalLatencyMs={}, errorType={}",
                    currentUserId,
                    knowledgeBaseId,
                    nanosToMillis(retrievalNanos),
                    generationNanos < 0 ? null : nanosToMillis(generationNanos),
                    nanosToMillis(totalNanos),
                    failure.getClass().getSimpleName());
        } catch (RuntimeException observabilityFailure) {
            log.warn("Failed to record RAG failure metric, errorType={}",
                    observabilityFailure.getClass().getSimpleName());
        }
    }

    private List<RetrievalSearchItem> filterByMinimumRelevance(
            List<RetrievalSearchItem> usableChunks
    ) {
        if (minimumRelevanceScore < 0) {
            return usableChunks;
        }
        return usableChunks.stream()
                .filter(item -> item.score() != null && item.score() >= minimumRelevanceScore)
                .toList();
    }

    private List<AiRagContextChunk> toAiContexts(List<RetrievalSearchItem> chunks) {
        return chunks.stream()
                .map(item -> new AiRagContextChunk(
                        item.docId(),
                        item.chunkId(),
                        item.fileName(),
                        item.pageNo(),
                        item.chunkIndex(),
                        item.score(),
                        item.text()
                ))
                .toList();
    }

    private ValidatedGeneration validateGenerationResponse(
            AiRagGenerateResponse response,
            List<RetrievalSearchItem> generationChunks
    ) {
        if (response == null
                || !StringUtils.hasText(response.answer())
                || !StringUtils.hasText(response.answerStatus())
                || response.citedContextIndexes() == null) {
            throw invalidGenerationResponse();
        }

        if (ANSWERED.equals(response.answerStatus())) {
            if (StringUtils.hasText(response.noAnswerReason())) {
                throw invalidGenerationResponse();
            }
            List<RagCitation> citations = citedChunks(
                    response.citedContextIndexes(),
                    generationChunks
            );
            if (citations.isEmpty()) {
                throw invalidGenerationResponse();
            }
            return new ValidatedGeneration(
                    response.answer().trim(),
                    ANSWERED,
                    false,
                    null,
                    "answered",
                    citations
            );
        }

        if (INSUFFICIENT_CONTEXT.equals(response.answerStatus())) {
            if (!response.citedContextIndexes().isEmpty()
                    || !StringUtils.hasText(response.noAnswerReason())) {
                throw invalidGenerationResponse();
            }
            return new ValidatedGeneration(
                    response.answer().trim(),
                    INSUFFICIENT_CONTEXT,
                    true,
                    response.noAnswerReason().trim(),
                    "insufficient_context",
                    List.of()
            );
        }

        throw invalidGenerationResponse();
    }

    private List<RagCitation> citedChunks(
            List<Integer> citedContextIndexes,
            List<RetrievalSearchItem> generationChunks
    ) {
        Set<Integer> uniqueIndexes = new LinkedHashSet<>(citedContextIndexes);
        if (uniqueIndexes.contains(null)) {
            throw invalidGenerationResponse();
        }

        return uniqueIndexes.stream()
                .map(index -> {
                    if (index < 1 || index > generationChunks.size()) {
                        throw invalidGenerationResponse();
                    }
                    return RagCitation.from(index, generationChunks.get(index - 1));
                })
                .toList();
    }

    private BusinessException invalidGenerationResponse() {
        return new BusinessException(
                ErrorCode.AI_SERVICE_UNAVAILABLE,
                "AI service returned an invalid RAG generation response"
        );
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private long nanosToMillis(long durationNanos) {
        return Math.max(0L, durationNanos) / 1_000_000L;
    }

    private record ValidatedGeneration(
            String answer,
            String answerStatus,
            Boolean noAnswer,
            String noAnswerReason,
            String outcome,
            List<RagCitation> citations
    ) {
    }
}
