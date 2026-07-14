package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiRagContextChunk(
        @JsonProperty("doc_id") Long docId,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("page_no") Integer pageNo,
        @JsonProperty("chunk_index") Integer chunkIndex,
        Double score,
        String text
) {
}
