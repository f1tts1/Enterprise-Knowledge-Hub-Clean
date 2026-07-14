package com.example.ekb.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Java 调用 Python 文档索引接口时使用的请求 DTO。
 *
 * <p>这里刻意只传“定位和归属信息”，不传文件字节：
 * Java 已经把文件上传到 MinIO，Python 根据 bucket/objectKey 自己下载。
 * 这样可以减少服务间传输压力，也让异步 worker 更接近真实生产系统。</p>
 *
 * <p>{@link JsonProperty} 用来显式固定 Java camelCase 和 Python snake_case 的字段契约。
 * 后续即使 Java 字段重命名，也不应该轻易改变 HTTP JSON 字段名。</p>
 */
public record AiDocumentIndexRequest(
        // Java 创建的索引任务 id。Python 不维护任务状态，只原样返回给 Java 做状态更新。
        @JsonProperty("task_id") Long taskId,
        // MySQL document.id，用于后续 Qdrant payload 关联业务文档。
        @JsonProperty("document_id") Long documentId,
        // 知识库 id，后续写入 Qdrant metadata filter，实现知识库隔离检索。
        @JsonProperty("kb_id") Long kbId,
        // 文档拥有者。V1 是个人知识库模型，后续可扩展为 tenant/team/RBAC。
        @JsonProperty("owner_user_id") Long ownerUserId,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("file_size") Long fileSize,
        // MinIO bucket 和 objectKey 是 Python 下载文件的最小必要信息。
        String bucket,
        @JsonProperty("object_key") String objectKey,
        // checksum 当前用于链路调试和完整性追踪，后续也可以做重复索引保护。
        @JsonProperty("checksum_sha256") String checksumSha256
) {
}
