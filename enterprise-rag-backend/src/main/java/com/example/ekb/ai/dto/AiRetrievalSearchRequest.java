package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Java 调 Python 检索接口的内部 DTO。
 *
 * <p>ownerUserId 和 kbId 由 Java 在完成权限校验后传入，Python 必须把它们
 * 下推到 Qdrant payload filter。这样即使向量库里有多个用户的数据，
 * 检索阶段也不会召回越权 chunk。</p>
 */
public record AiRetrievalSearchRequest(
        @JsonProperty("owner_user_id") Long ownerUserId,
        @JsonProperty("kb_id") Long kbId,
        String query,
        @JsonProperty("top_k") Integer topK
) {
}
