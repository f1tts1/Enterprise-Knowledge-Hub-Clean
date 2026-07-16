package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python 文档索引接口返回给 Java 的响应 DTO。
 *
 * <p>V2 阶段 Python 会在生成 embedding 后写入 Qdrant。Java 只有收到 INDEXED
 * 并确认 indexed_chunk_count 与 chunk_count 对齐，才会把 document/indexing_task
 * 标记为成功。这样可以避免“只生成了向量但没有进入向量库”时误报完成。</p>
 */
public record AiDocumentIndexResponse(
        @JsonProperty("task_id") Long taskId,
        @JsonProperty("document_id") Long documentId,
        // 允许值由 IndexingServiceImpl.ensureIndexed 统一校验，避免 Python 契约漂移。
        String status,
        String message,
        // page/char/chunk 统计用于本地测试和后续管理台展示索引进度。
        @JsonProperty("page_count") Integer pageCount,
        @JsonProperty("char_count") Integer charCount,
        @JsonProperty("chunk_count") Integer chunkCount,
        // embedding 统计决定当前 Qdrant collection 的 vector size。
        @JsonProperty("embedded_chunk_count") Integer embeddedChunkCount,
        // indexedChunkCount 是 Qdrant 成功写入的 chunk 数，是 V2 成功终态的关键依据。
        @JsonProperty("indexed_chunk_count") Integer indexedChunkCount,
        @JsonProperty("embedding_provider") String embeddingProvider,
        @JsonProperty("embedding_model") String embeddingModel,
        @JsonProperty("vector_dim") Integer vectorDim,
        @JsonProperty("vector_store") String vectorStore,
        @JsonProperty("vector_collection") String vectorCollection,
        // preview 只用于调试，不应作为业务正文持久化。
        @JsonProperty("text_preview") String textPreview,
        @JsonProperty("chunk_preview") String chunkPreview,
        @JsonProperty("download_latency_ms") Long downloadLatencyMs,
        @JsonProperty("parse_latency_ms") Long parseLatencyMs,
        @JsonProperty("split_latency_ms") Long splitLatencyMs,
        @JsonProperty("embedding_latency_ms") Long embeddingLatencyMs,
        @JsonProperty("vector_store_latency_ms") Long vectorStoreLatencyMs,
        @JsonProperty("total_latency_ms") Long totalLatencyMs
) {
}
