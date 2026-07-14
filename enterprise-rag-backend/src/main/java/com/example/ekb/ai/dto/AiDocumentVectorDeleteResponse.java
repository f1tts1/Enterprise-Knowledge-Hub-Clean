package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python 删除 Qdrant vectors 后返回给 Java 的响应 DTO。
 *
 * <p>删除接口按幂等语义处理：collection 不存在或目标文档没有 vectors 时，
 * 也表示删除后的目标状态已经达成。</p>
 */
public record AiDocumentVectorDeleteResponse(
        @JsonProperty("document_id") Long documentId,
        String status,
        String message,
        @JsonProperty("vector_store") String vectorStore,
        @JsonProperty("vector_collection") String vectorCollection,
        @JsonProperty("collection_existed") Boolean collectionExisted
) {
}
