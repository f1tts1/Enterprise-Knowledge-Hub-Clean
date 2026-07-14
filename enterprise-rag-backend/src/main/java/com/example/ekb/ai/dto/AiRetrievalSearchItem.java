package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python 从 Qdrant 返回的单条 chunk 命中。
 *
 * <p>这些字段来自 Qdrant payload，不直接落 MySQL。Java 会把它们透传给调用方，
 * 后续 RAG 问答可复用 docId/pageNo/chunkIndex 生成引用。</p>
 */
public record AiRetrievalSearchItem(
        @JsonProperty("point_id") String pointId,
        Double score,
        @JsonProperty("doc_id") Long docId,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("page_no") Integer pageNo,
        @JsonProperty("chunk_index") Integer chunkIndex,
        @JsonProperty("char_start") Integer charStart,
        @JsonProperty("char_end") Integer charEnd,
        String text
) {
}
