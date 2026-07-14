package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Java 删除文档后调用 Python 清理 Qdrant vectors 的请求 DTO。
 *
 * <p>这里同时携带 documentId、kbId 和 ownerUserId，而不是只携带 documentId。
 * documentId 在当前 MySQL 中虽然是全局自增，但向量库删除属于高风险写操作，
 * filter 中保留完整业务归属字段可以避免内部调用错误造成跨用户或跨知识库误删。</p>
 */
public record AiDocumentVectorDeleteRequest(
        @JsonProperty("document_id") Long documentId,
        @JsonProperty("kb_id") Long kbId,
        @JsonProperty("owner_user_id") Long ownerUserId
) {
}
