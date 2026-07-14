package com.example.ekb.rag.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import com.example.ekb.ai.client.AiRagGenerateClient;
import com.example.ekb.ai.dto.AiRagContextChunk;
import com.example.ekb.ai.dto.AiRagGenerateRequest;
import com.example.ekb.ai.dto.AiRagGenerateResponse;
import com.example.ekb.rag.dto.RagAskRequest;
import com.example.ekb.rag.dto.RagAskResponse;
import com.example.ekb.rag.dto.RagCitation;
import com.example.ekb.rag.service.RagService;
import com.example.ekb.retrieval.dto.RetrievalSearchItem;
import com.example.ekb.retrieval.dto.RetrievalSearchRequest;
import com.example.ekb.retrieval.dto.RetrievalSearchResponse;
import com.example.ekb.retrieval.service.RetrievalService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RagServiceImpl implements RagService {

    private static final int DEFAULT_TOP_K = 5;
    private static final String NO_CONTEXT_ANSWER = "当前知识库没有可用于回答该问题的已索引内容。";

    private final RetrievalService retrievalService;
    private final AiRagGenerateClient aiRagGenerateClient;

    public RagServiceImpl(RetrievalService retrievalService, AiRagGenerateClient aiRagGenerateClient) {
        this.retrievalService = retrievalService;
        this.aiRagGenerateClient = aiRagGenerateClient;
    }

    @Override
    public RagAskResponse ask(Long currentUserId, Long knowledgeBaseId, RagAskRequest request) {
        String question = request.question().trim();
        Integer topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
        RetrievalSearchResponse retrievalResponse = retrievalService.search(
                currentUserId,
                knowledgeBaseId,
                new RetrievalSearchRequest(question, topK)
        );

        List<RetrievalSearchItem> chunks = retrievalResponse.records() == null
                ? List.of()
                : retrievalResponse.records();
        List<RagCitation> citations = toCitations(chunks);
        if (chunks.isEmpty()) {
            return new RagAskResponse(
                    question,
                    NO_CONTEXT_ANSWER,
                    topK,
                    null,
                    null,
                    retrievalResponse.vectorStore(),
                    retrievalResponse.vectorCollection(),
                    citations,
                    chunks
            );
        }

        List<AiRagContextChunk> contexts = toAiContexts(chunks);
        if (contexts.isEmpty()) {
            return new RagAskResponse(
                    question,
                    NO_CONTEXT_ANSWER,
                    topK,
                    null,
                    null,
                    retrievalResponse.vectorStore(),
                    retrievalResponse.vectorCollection(),
                    citations,
                    chunks
            );
        }

        AiRagGenerateResponse generateResponse = aiRagGenerateClient.generate(new AiRagGenerateRequest(
                question,
                contexts
        ));
        return new RagAskResponse(
                question,
                answerOrFallback(generateResponse),
                topK,
                generateResponse == null ? null : generateResponse.llmProvider(),
                generateResponse == null ? null : generateResponse.llmModel(),
                retrievalResponse.vectorStore(),
                retrievalResponse.vectorCollection(),
                citations,
                chunks
        );
    }

    private List<AiRagContextChunk> toAiContexts(List<RetrievalSearchItem> chunks) {
        return chunks.stream()
                .filter(item -> StringUtils.hasText(item.text()))
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

    private List<RagCitation> toCitations(List<RetrievalSearchItem> chunks) {
        return IntStream.range(0, chunks.size())
                .mapToObj(index -> RagCitation.from(index + 1, chunks.get(index)))
                .toList();
    }

    private String answerOrFallback(AiRagGenerateResponse generateResponse) {
        if (generateResponse == null || !StringUtils.hasText(generateResponse.answer())) {
            return "LLM 未返回可用答案。";
        }
        return Objects.requireNonNull(generateResponse.answer()).trim();
    }
}
