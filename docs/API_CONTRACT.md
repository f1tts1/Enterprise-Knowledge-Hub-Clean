# API 契约

本文档记录当前已经实现的 API。未实现的接口不列为可用接口。

## 通用约定

### Java API Base URL

```text
http://localhost:8080
```

### Python API Base URL

```text
http://localhost:8000
```

### Java 统一响应结构

所有 Java 后端接口返回：

```json
{
  "success": true,
  "code": "0",
  "message": "success",
  "data": {},
  "requestId": "generated-or-client-provided-id",
  "timestamp": "2026-06-15T00:00:00Z"
}
```

失败响应：

```json
{
  "success": false,
  "code": "400001",
  "message": "Validation failed",
  "data": null,
  "requestId": "generated-or-client-provided-id",
  "timestamp": "2026-06-15T00:00:00Z"
}
```

客户端可传入 `X-Request-Id`。如果不传，Java 后端生成 requestId，并在响应 body 和 header 中返回。

### 鉴权

匿名接口：

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`

其它 Java API 必须携带：

```http
Authorization: Bearer <accessToken>
```

Java 解析 JWT 后会加载当前用户快照；当前实现优先读取 Redis 认证缓存，缓存未命中或 Redis 不可用时回源 MySQL。该缓存不改变接口契约，也不保存密码哈希。

知识库 owner 权限校验仍由 Java 完成；当前实现会优先读取 Redis 知识库访问缓存，缓存未命中或 Redis 不可用时回源 MySQL。该缓存不改变 API 响应语义，访问不存在、已删除或不属于当前用户的知识库仍返回 not found 风格错误。

当前没有 health 接口。

## Java API

### 注册用户

```http
POST /api/v1/auth/register
Content-Type: application/json
```

请求：

```json
{
  "username": "alice",
  "password": "Password123",
  "nickname": "Alice",
  "email": "alice@example.com"
}
```

响应 `data`：

```json
{
  "id": 1,
  "username": "alice",
  "nickname": "Alice",
  "email": "alice@example.com",
  "status": "ACTIVE"
}
```

### 登录

```http
POST /api/v1/auth/login
Content-Type: application/json
```

请求：

```json
{
  "username": "alice",
  "password": "Password123"
}
```

响应 `data`：

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<refresh-token>",
  "tokenType": "Bearer",
  "expiresInSeconds": 1800,
  "refreshExpiresInSeconds": 1209600,
  "user": {
    "id": 1,
    "username": "alice",
    "nickname": "Alice",
    "email": "alice@example.com",
    "status": "ACTIVE"
  }
}
```

### 刷新 Token

```http
POST /api/v1/auth/refresh
Content-Type: application/json
```

请求：

```json
{
  "refreshToken": "<refresh-token>"
}
```

响应 `data`：

```json
{
  "accessToken": "<new-jwt>",
  "refreshToken": "<new-refresh-token>",
  "tokenType": "Bearer",
  "expiresInSeconds": 1800,
  "refreshExpiresInSeconds": 1209600,
  "user": {
    "id": 1,
    "username": "alice",
    "nickname": "Alice",
    "email": "alice@example.com",
    "status": "ACTIVE"
  }
}
```

刷新会轮换 refresh token：旧 refresh token 被 Redis 撤销，新 refresh token 写入 Redis。

### 退出登录

```http
POST /api/v1/auth/logout
Content-Type: application/json
```

请求：

```json
{
  "refreshToken": "<refresh-token>"
}
```

响应 `data`：

```json
null
```

退出登录只撤销当前 refresh token。当前 access token 不写入 Redis 黑名单，会等待自然过期。

### 当前用户

```http
GET /api/v1/users/me
Authorization: Bearer <accessToken>
```

响应 `data`：

```json
{
  "id": 1,
  "username": "alice",
  "nickname": "Alice",
  "email": "alice@example.com",
  "status": "ACTIVE"
}
```

### 创建知识库

```http
POST /api/v1/knowledge-bases
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "name": "Java Backend Notes",
  "description": "Interview project knowledge base"
}
```

响应 `data`：

```json
{
  "id": 1,
  "name": "Java Backend Notes",
  "description": "Interview project knowledge base",
  "visibility": "PRIVATE",
  "status": "ACTIVE",
  "createdAt": "2026-06-15T10:00:00",
  "updatedAt": "2026-06-15T10:00:00"
}
```

### 查询知识库列表

```http
GET /api/v1/knowledge-bases?page=1&size=10
Authorization: Bearer <accessToken>
```

参数：

- `page` 默认 1，最小 1。
- `size` 默认 10，范围 1 到 100。

响应 `data`：

```json
{
  "records": [
    {
      "id": 1,
      "name": "Java Backend Notes",
      "description": "Interview project knowledge base",
      "visibility": "PRIVATE",
      "status": "ACTIVE",
      "createdAt": "2026-06-15T10:00:00",
      "updatedAt": "2026-06-15T10:00:00"
    }
  ],
  "page": 1,
  "size": 10,
  "total": 1
}
```

### 查询知识库详情

```http
GET /api/v1/knowledge-bases/{id}
Authorization: Bearer <accessToken>
```

响应 `data` 同创建知识库响应。

如果知识库不存在、已删除或不属于当前用户，返回 `404001`。

### 删除知识库

```http
DELETE /api/v1/knowledge-bases/{id}
Authorization: Bearer <accessToken>
```

当前行为：

- 如果知识库下仍有未删除文档，返回 `409 Conflict`，要求先删除文档。
- 逻辑删除知识库。
- 将知识库状态改为 `ARCHIVED`。
- 将名称追加 `#deleted#id`，释放原名称唯一约束。
- 当前不会级联删除知识库下所有文档或 Qdrant points。

### 上传文档

```http
POST /api/v1/knowledge-bases/{kbId}/documents
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data
```

请求：

```text
file=<PDF/DOCX/TXT/MD/Markdown 文件>
```

限制：

- 最大 20MB。
- 支持扩展名：`pdf`、`docx`、`txt`、`md`、`markdown`。
- 同一知识库内只对未删除文档按 SHA-256 去重；已删除历史文档保留 checksum，但不阻止用户再次上传同内容文件。
- 并发上传同内容文件时，服务端先通过 MySQL active checksum 唯一索引抢占，再写 MinIO object；未抢占成功的请求返回 `409003`，不会先写出额外 MinIO object。

响应 `data`：

```json
{
  "id": 1,
  "kbId": 1,
  "fileName": "example.txt",
  "contentType": "text/plain",
  "fileSize": 123,
  "bucket": "ekb-documents",
  "objectKey": "users/1/knowledge-bases/1/documents/<uuid>.txt",
  "checksumSha256": "<64-char-sha256>",
  "indexStatus": "PENDING_INDEX",
  "chunkCount": 0,
  "errorMessage": null,
  "indexingTaskId": 1,
  "createdAt": "2026-06-15T10:00:00",
  "updatedAt": "2026-06-15T10:00:00"
}
```

副作用：

1. Java 上传文件到 MinIO。
2. Java 写入 `document`。
3. Java 写入 `indexing_task`。
4. MySQL 事务提交后，Java 向 RabbitMQ 写入索引任务消息。

### 查询知识库文档列表

```http
GET /api/v1/knowledge-bases/{kbId}/documents?page=1&size=10
Authorization: Bearer <accessToken>
```

响应 `data`：

```json
{
  "records": [
    {
      "id": 1,
      "kbId": 1,
      "fileName": "example.txt",
      "contentType": "text/plain",
      "fileSize": 123,
      "bucket": "ekb-documents",
      "objectKey": "users/1/knowledge-bases/1/documents/<uuid>.txt",
      "checksumSha256": "<64-char-sha256>",
      "indexStatus": "INDEXED",
      "chunkCount": 3,
      "errorMessage": null,
      "indexingTaskId": null,
      "createdAt": "2026-06-15T10:00:00",
      "updatedAt": "2026-06-15T10:00:00"
    }
  ],
  "page": 1,
  "size": 10,
  "total": 1
}
```

### 查询文档详情

```http
GET /api/v1/documents/{documentId}
Authorization: Bearer <accessToken>
```

响应 `data` 同文档列表记录。

如果文档不存在、已删除或不属于当前用户，返回 `404002`。

### 查询文档索引状态

```http
GET /api/v1/documents/{documentId}/index-status
Authorization: Bearer <accessToken>
```

响应 `data`：

```json
{
  "documentId": 1,
  "documentIndexStatus": "INDEXED",
  "chunkCount": 3,
  "documentErrorMessage": null,
  "indexingTaskId": 1,
  "taskStatus": "SUCCESS",
  "retryCount": 0,
  "maxRetry": 3,
  "taskErrorMessage": null,
  "startedAt": "2026-06-15T10:00:01",
  "finishedAt": "2026-06-15T10:00:10",
  "updatedAt": "2026-06-15T10:00:10"
}
```

### 重试文档索引

```http
POST /api/v1/documents/{documentId}/index-retry
Authorization: Bearer <accessToken>
```

当前行为：

- 只允许当前用户重试自己的文档。
- 允许 `document.index_status=PENDING_INDEX` 且最新 `indexing_task.status=PENDING` 的文档手动重投 RabbitMQ 消息。
- `PENDING_INDEX/PENDING` 手动重投不增加 `retry_count`，因为任务还没有真正执行失败。
- 允许 `document.index_status=INDEX_FAILED` 且最新 `indexing_task.status=FAILED` 的文档重试。
- 失败重试时将文档重新置为 `PENDING_INDEX`，将任务重新置为 `PENDING`，`retry_count + 1`，并清空错误信息、`started_at`、`finished_at`。
- 重试复用现有 RabbitMQ 投递逻辑；如果 RabbitMQ 暂时不可用，任务保持 `PENDING`，由 PENDING 任务重投器继续投递。
- 如果文档处于 `INDEXING`，返回 `409004 DOCUMENT_BUSY`。
- 如果文档不是可重投或可重试状态，返回 `409 Conflict`。

响应 `data` 同“查询文档索引状态”。

### 删除文档

```http
DELETE /api/v1/documents/{documentId}
Authorization: Bearer <accessToken>
```

当前行为：

- 如果文档处于 `PENDING_INDEX`、`INDEXING`，返回 `409004 DOCUMENT_BUSY`，V1 不支持同一文档同时索引和删除。
- 先将 MySQL `document` 置为 `DELETING` 且 `is_deleted=1`，使文档立即对列表、详情和检索不可见。
- 同步调用 Python 内部接口，按 `ownerUserId + kbId + docId` 删除 Qdrant vectors。
- 删除 MinIO object。对象不存在时视为成功。
- 全部删除完成后，将 `document.index_status` 置为 `DELETED`。
- 如果 Qdrant 或 MinIO 删除失败，文档保持 `is_deleted=1` 并进入 `DELETE_FAILED`，再次调用删除接口可重试。
- 删除后的历史记录不再参与 checksum 去重唯一约束，因此同一知识库可以再次上传同内容文件，并且后续删除不会与历史 deleted 行冲突。

### 知识库内向量检索

```http
POST /api/v1/knowledge-bases/{kbId}/retrieval/search
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "query": "RabbitMQ 在异步索引里承担什么职责？",
  "topK": 5
}
```

校验：

- `query` 必填，最长 512。
- `topK` 可空，默认 5，范围 1 到 20。

响应 `data`：

```json
{
  "query": "RabbitMQ 在异步索引里承担什么职责？",
  "topK": 5,
  "vectorStore": "qdrant",
  "vectorCollection": "rag_chunks_v1",
  "records": [
    {
      "pointId": "uuid",
      "score": 0.78,
      "docId": 1,
      "chunkId": "doc-1-chunk-0",
      "fileName": "alice-rabbitmq-indexing.txt",
      "pageNo": 1,
      "chunkIndex": 0,
      "charStart": 0,
      "charEnd": 300,
      "text": "chunk text"
    }
  ]
}
```

权限约定：

- Java 先校验当前用户拥有 `{kbId}`。
- 如果 `{kbId}` 不存在、已删除或不属于当前用户，返回 `404001`。
- Python 检索时必须使用 `ownerUserId + kbId` Qdrant filter。
- Java 返回前会再按 MySQL `document.owner_user_id + kb_id + is_deleted=0 + index_status=INDEXED` 过滤命中结果，防止未完成索引、失败索引或已删除文档的历史 vectors 被返回。
- 当前接口只返回检索 chunk，不生成 LLM 答案。

### 知识库 RAG 问答

```http
POST /api/v1/knowledge-bases/{kbId}/rag/ask
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "question": "RabbitMQ 在这个项目里解决了什么问题？",
  "topK": 5
}
```

校验：

- `question` 必填，最长 512。
- `topK` 可空，默认 5，范围 1 到 20。

响应 `data`：

```json
{
  "question": "RabbitMQ 在这个项目里解决了什么问题？",
  "answer": "RabbitMQ 用于把文档上传和索引处理解耦... [片段 1]",
  "topK": 5,
  "llmProvider": "openai-compatible",
  "llmModel": "your-chat-model",
  "vectorStore": "qdrant",
  "vectorCollection": "rag_chunks_v1",
  "citations": [
    {
      "index": 1,
      "docId": 1,
      "chunkId": "doc-1-chunk-0",
      "fileName": "example.txt",
      "score": 0.78,
      "pageNo": 1,
      "chunkIndex": 0,
      "charStart": 0,
      "charEnd": 300,
      "text": "chunk text"
    }
  ],
  "retrievedChunks": [
    {
      "pointId": "uuid",
      "score": 0.78,
      "docId": 1,
      "chunkId": "doc-1-chunk-0",
      "fileName": "example.txt",
      "pageNo": 1,
      "chunkIndex": 0,
      "charStart": 0,
      "charEnd": 300,
      "text": "chunk text"
    }
  ]
}
```

当前行为：

- Java 先校验当前用户拥有 `{kbId}`。
- Java 复用 `retrieval/search` 服务获得已按 MySQL 二次过滤后的 chunk。
- 如果没有可用已索引 chunk，Java 不调用 LLM，直接返回无上下文答案，`citations=[]`、`retrievedChunks=[]`。
- 如果存在可用 chunk，Java 调用 Python 内部接口 `POST /api/v1/rag/generate` 生成答案。
- Python prompt 要求 LLM 使用 `[片段 n]` 标记说明答案依据；Java 响应中的 `citations[].index` 与该片段编号对应，`citations[].score` 为 Qdrant 检索分数。
- 当前是同步问答接口，不支持 SSE，不写入 conversation/chat_message/answer_citation。

错误：

- `{kbId}` 不存在、已删除或不属于当前用户，返回 `404001`。
- Python AI 服务不可用、LLM 未配置或 LLM 调用失败，返回 `503001`。

## Python API

Python API 当前是 Java 内部调用接口，不面向客户端直接暴露鉴权。

### 文档索引

```http
POST /api/v1/documents/index
Content-Type: application/json
```

请求：

```json
{
  "task_id": 1,
  "document_id": 1,
  "kb_id": 1,
  "owner_user_id": 1,
  "file_name": "example.txt",
  "content_type": "text/plain",
  "file_size": 123,
  "bucket": "ekb-documents",
  "object_key": "users/1/knowledge-bases/1/documents/<uuid>.txt",
  "checksum_sha256": "<64-char-sha256>"
}
```

响应：

```json
{
  "task_id": 1,
  "document_id": 1,
  "status": "INDEXED",
  "message": "文档已下载、解析、切分、生成 embedding 并写入 Qdrant。",
  "page_count": 1,
  "char_count": 1200,
  "chunk_count": 3,
  "embedded_chunk_count": 3,
  "indexed_chunk_count": 3,
  "embedding_provider": "local",
  "embedding_model": "BAAI/bge-small-zh-v1.5",
  "vector_dim": 512,
  "vector_store": "qdrant",
  "vector_collection": "rag_chunks_v1",
  "text_preview": "前 300 字以内文本预览",
  "chunk_preview": "第一个 chunk 文本"
}
```

Python schema 允许的状态：

- `ACCEPTED`：schema 保留状态，当前主流程不会返回它。
- `PARSED`：schema 保留状态，当前主流程不会返回它。
- `CHUNKED`：文件已下载、解析、切分，但未生成 embedding，通常是 `EMBEDDING_PROVIDER=none`。
- `EMBEDDED`：保留状态，表示已生成 embedding 但尚未写入 Qdrant；当前主流程不会返回它。
- `INDEXED`：文件已下载、解析、切分、生成 embedding，并将 chunk/vector/payload 写入 Qdrant。

Java 当前要求 Python 返回 `INDEXED` 才会把索引任务标记为成功。

Python 错误：

- 400：不支持的文档类型。
- 422：Pydantic 请求参数校验失败。
- 502：MinIO 下载失败或 Qdrant 访问失败。
- 500：模型目录缺失、依赖缺失或未捕获异常。

### 删除文档 vectors

```http
POST /api/v1/documents/delete-vectors
Content-Type: application/json
```

请求：

```json
{
  "document_id": 1,
  "kb_id": 1,
  "owner_user_id": 1
}
```

响应：

```json
{
  "document_id": 1,
  "status": "DELETED",
  "message": "文档对应的 Qdrant vectors 已按 ownerUserId、kbId 和 docId 清理。",
  "vector_store": "qdrant",
  "vector_collection": "rag_chunks_v1",
  "collection_existed": true
}
```

说明：

- 该接口只供 Java 内部调用。
- Python 不修改 MySQL，不删除 MinIO object。
- Qdrant collection 不存在或目标文档没有 vectors 时，按幂等成功处理。
- 响应不返回删除 point 数量，删除路径不为了日志额外执行 count 查询。
- 删除 filter 必须同时包含 `ownerUserId`、`kbId` 和 `docId`，避免误删其它业务边界的数据。

错误：

- 422：Pydantic 请求参数校验失败。
- 502：Qdrant 删除失败。

### 向量检索

```http
POST /api/v1/retrieval/search
Content-Type: application/json
```

请求：

```json
{
  "owner_user_id": 1,
  "kb_id": 1,
  "query": "RabbitMQ 异步索引",
  "top_k": 5
}
```

响应：

```json
{
  "query": "RabbitMQ 异步索引",
  "top_k": 5,
  "vector_store": "qdrant",
  "vector_collection": "rag_chunks_v1",
  "records": [
    {
      "point_id": "uuid",
      "score": 0.78,
      "doc_id": 1,
      "chunk_id": "doc-1-chunk-0",
      "file_name": "example.txt",
      "page_no": 1,
      "chunk_index": 0,
      "char_start": 0,
      "char_end": 300,
      "text": "chunk text"
    }
  ]
}
```

错误：

- 422：Pydantic 请求参数校验失败。
- 502：Qdrant 检索失败。
- 500：本地 embedding 模型缺失、依赖缺失或未捕获异常。

### RAG 答案生成

```http
POST /api/v1/rag/generate
Content-Type: application/json
```

请求：

```json
{
  "question": "RabbitMQ 在这个项目里解决了什么问题？",
  "contexts": [
    {
      "doc_id": 1,
      "chunk_id": "doc-1-chunk-0",
      "file_name": "example.txt",
      "page_no": 1,
      "chunk_index": 0,
      "score": 0.78,
      "text": "chunk text"
    }
  ]
}
```

响应：

```json
{
  "answer": "RabbitMQ 用于把文档上传和索引处理解耦...",
  "llm_provider": "openai-compatible",
  "llm_model": "your-chat-model"
}
```

说明：

- 该接口只供 Java 内部调用。
- Python 不校验用户权限，也不访问 MySQL；权限过滤和 chunk 选择已经由 Java 完成。
- 当前只支持 OpenAI-compatible Chat Completions。
- 可显式配置 `LLM_PROVIDER=openai-compatible`、`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`。
- 如果本机存在 `DEEPSEEK_API_KEY`，Python 会自动使用 DeepSeek 默认配置：`LLM_BASE_URL=https://api.deepseek.com/v1`、`LLM_MODEL=deepseek-chat`。

错误：

- 422：Pydantic 请求参数校验失败。
- 503：LLM provider 未配置或 LLM 调用失败。

## MySQL 约束

### document checksum 去重

`document` 表使用生成列 `active_checksum_sha256` 支撑未删除文档去重：

```sql
active_checksum_sha256 =
  CASE WHEN is_deleted = 0 THEN checksum_sha256 ELSE NULL END
```

唯一索引：

```text
uk_doc_kb_active_checksum (kb_id, active_checksum_sha256)
```

含义：

- 同一知识库内只能存在一条未删除的同 SHA-256 文档。
- 已删除文档的 `active_checksum_sha256` 为 `NULL`，不参与唯一冲突。
- 已删除历史行仍保留真实 `checksum_sha256`，便于排查和审计。
- 上传流程先插入 `document` 抢占 active checksum，再写 MinIO object，避免并发同内容上传时失败请求先生成孤儿 object。

## Java 与 Python 通信协议

### RabbitMQ 索引任务消息

RabbitMQ 配置：

```text
exchange: ekb.indexing.exchange
queue: ekb.indexing.tasks
routingKey: indexing.task
deadLetterExchange: ekb.indexing.dlx
deadLetterQueue: ekb.indexing.tasks.dlq
deadLetterRoutingKey: indexing.task.dead
```

消息字段：

```json
{
  "documentId": "1",
  "indexingTaskId": "1"
}
```

说明：

- RabbitMQ 消息只保存 ID。
- Java 消费者重新查 MySQL 获取 bucket、objectKey、文件名、checksum。
- Java 消费者正常处理后手动 ack；消费者自身异常或非法消息进入 DLQ。
- Python 不访问 MySQL。

### Java 调 Python 文档索引 DTO 映射

| Java 字段 | JSON 字段 | Python 字段 |
|---|---|---|
| taskId | task_id | task_id |
| documentId | document_id | document_id |
| kbId | kb_id | kb_id |
| ownerUserId | owner_user_id | owner_user_id |
| fileName | file_name | file_name |
| contentType | content_type | content_type |
| fileSize | file_size | file_size |
| bucket | bucket | bucket |
| objectKey | object_key | object_key |
| checksumSha256 | checksum_sha256 | checksum_sha256 |

### Java 调 Python 删除文档 vectors DTO 映射

| Java 字段 | JSON 字段 | Python 字段 |
|---|---|---|
| documentId | document_id | document_id |
| kbId | kb_id | kb_id |
| ownerUserId | owner_user_id | owner_user_id |

### Java 调 Python 检索 DTO 映射

| Java 字段 | JSON 字段 | Python 字段 |
|---|---|---|
| ownerUserId | owner_user_id | owner_user_id |
| kbId | kb_id | kb_id |
| query | query | query |
| topK | top_k | top_k |

### Java 调 Python RAG 生成 DTO 映射

| Java 字段 | JSON 字段 | Python 字段 |
|---|---|---|
| question | question | question |
| contexts[].docId | contexts[].doc_id | contexts[].doc_id |
| contexts[].chunkId | contexts[].chunk_id | contexts[].chunk_id |
| contexts[].fileName | contexts[].file_name | contexts[].file_name |
| contexts[].pageNo | contexts[].page_no | contexts[].page_no |
| contexts[].chunkIndex | contexts[].chunk_index | contexts[].chunk_index |
| contexts[].score | contexts[].score | contexts[].score |
| contexts[].text | contexts[].text | contexts[].text |

### Java 处理 Python 索引响应

Java 成功状态要求：

- `status` 必须为 `INDEXED`。
- `chunk_count` 必须大于 0。
- `indexed_chunk_count` 必须大于 0。
- `indexed_chunk_count` 必须等于 `chunk_count`。

如果响应为空、状态不是 `INDEXED`，或写入数量与 chunk 数量不一致，Java 将文档标记为 `INDEX_FAILED`，将索引任务标记为 `FAILED`。

当前 Java 持久化 `document.chunk_count` 和索引成功/失败状态，不持久化 `embedded_chunk_count`、`indexed_chunk_count` 或 `vector_dim`。

## Qdrant Schema

默认 collection：

```text
rag_chunks_v1
```

默认向量配置：

- `size`：由本地 embedding 模型实际输出决定，`BAAI/bge-small-zh-v1.5` 通常为 512。
- `distance`：`Cosine`。

Point id：

- 使用固定 namespace + `doc-{documentId}-chunk-{chunkIndex}` 生成稳定 UUID。
- 同一文档重试索引会覆盖同一批 point，并先按 `ownerUserId + kbId + docId` 删除旧 point，避免重试后 chunk 数变少时残留旧高序号 chunk。
- 删除文档时按 `ownerUserId + kbId + docId` filter 删除该文档所有 point。

Point payload：

```json
{
  "ownerUserId": 1,
  "kbId": 1,
  "docId": 1,
  "taskId": 1,
  "chunkId": "doc-1-chunk-0",
  "fileName": "example.txt",
  "contentType": "text/plain",
  "fileSize": 123,
  "bucket": "ekb-documents",
  "objectKey": "users/1/knowledge-bases/1/documents/<uuid>.txt",
  "checksumSha256": "<64-char-sha256>",
  "pageNo": 1,
  "chunkIndex": 0,
  "charStart": 0,
  "charEnd": 120,
  "text": "chunk text",
  "embeddingProvider": "local",
  "embeddingModel": "BAAI/bge-small-zh-v1.5",
  "createdAt": "2026-06-15T00:00:00+00:00"
}
```

## 状态约定

### document.index_status

SQL 注释定义：

- `PENDING_INDEX`
- `INDEXING`
- `INDEXED`
- `INDEX_FAILED`
- `DELETING`
- `DELETED`
- `DELETE_FAILED`

当前代码实际写入：

- `PENDING_INDEX`：上传主流程完成，等待异步索引。
- `INDEXING`：Java 消费索引任务并正在调用 Python。
- `INDEXED`：Python 已完成 Qdrant 写入，Java 已确认写入数量。
- `INDEX_FAILED`：索引调用失败、响应不符合契约或索引超时。
- `DELETING`：删除流程已开始，文档已对业务不可见。
- `DELETED`：Qdrant vectors 和 MinIO object 清理完成。
- `DELETE_FAILED`：删除 Qdrant vectors 或 MinIO object 失败，文档仍保持业务不可见，可再次 DELETE 重试。

说明：

- 检索结果返回前只认可 `INDEXED` 且 `is_deleted=0` 的文档。

### indexing_task.status

SQL 注释定义：

- `PENDING`
- `RUNNING`
- `SUCCESS`
- `FAILED`

当前代码实际写入：

- `PENDING`：上传后创建任务，或索引失败后人工重试。
- `RUNNING`：Java 消费任务并开始调用 Python。
- `SUCCESS`：Python 已完成 Qdrant 写入，Java 已确认写入数量。
- `FAILED`：索引调用失败、响应不符合契约、文档删除或索引超时。

### chat_message.role

SQL 注释定义：

- `USER`
- `ASSISTANT`
- `SYSTEM`

当前没有 chat message API。

## 错误码

| code | HTTP | 含义 |
|---|---:|---|
| 0 | 200 | success |
| 400 | 400 | Bad request |
| 400001 | 400 | Validation failed |
| 401 | 401 | Unauthorized |
| 401001 | 401 | Invalid username or password |
| 401002 | 401 | Invalid token |
| 403 | 403 | Forbidden |
| 403001 | 403 | User is disabled |
| 404 | 404 | Resource not found |
| 404001 | 404 | Knowledge base not found |
| 404002 | 404 | Document not found |
| 409 | 409 | Conflict |
| 409001 | 409 | Username already exists |
| 409002 | 409 | Knowledge base name already exists |
| 409003 | 409 | Document already exists in this knowledge base |
| 409004 | 409 | Document is being processed |
| 400002 | 400 | Document type is not supported |
| 400003 | 400 | Document file is too large |
| 503001 | 503 | AI service unavailable |
| 503002 | 503 | Storage operation failed |
| 503003 | 503 | Auth token store unavailable |
| 500 | 500 | Internal server error |
