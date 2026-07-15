# 当前系统架构

本文档记录当前仓库已经落地的架构。未实现的能力只在“未落地”中说明，不作为当前架构的一部分。

## 架构图

```text
Browser Console / curl
        |
        | HTTP + JSON / multipart
        v
Spring Boot Backend :8080
        |
        | MyBatis-Plus
        v
MySQL
        |
        | user / knowledge_base / document / indexing_task
        |
        +------ MinIO SDK ------> MinIO :9000
        |                         存储上传原始文件
        |
        +------ publish ---------> RabbitMQ
        |                         exchange: ekb.indexing.exchange
        |                         queue: ekb.indexing.tasks
        |                                  |
        |                                  | manual ack / DLQ
        |                                  v
        |                         Spring Boot Indexing Consumer
        |                                  |
        |                                  | WebClient
        |                                  v
        +----------------------> FastAPI AI Service :8000
                                   |
                                   | MinIO SDK
                                   v
                                MinIO
                                   |
                                   | 下载文件 -> 解析 -> 切分 -> 本地 embedding
                                   v
                                Qdrant :6333
                                   |
                                   | chunk/vector/payload 写入成功
                                   v
                                Python response: INDEXED + statistics
                                   |
                                   v
                         Spring Boot 更新 MySQL 索引状态

Client
  |
  | POST /api/v1/knowledge-bases/{kbId}/retrieval/search
  v
Spring Boot 校验 JWT + 知识库 owner
  |
  | WebClient POST /api/v1/retrieval/search
  v
FastAPI query embedding + Qdrant filter(ownerUserId, kbId)
  |
  v
返回 topK chunk，不生成 LLM 答案

Client
  |
  | POST /api/v1/knowledge-bases/{kbId}/rag/ask
  v
Spring Boot 校验 JWT + 知识库 owner -> 复用 retrieval/search
  |
  | WebClient POST /api/v1/rag/generate
  v
FastAPI 调 OpenAI-compatible Chat Completions
  |
  v
返回 answer + citations + retrievedChunks

Client
  |
  | DELETE /api/v1/documents/{documentId}
  v
Spring Boot 校验 JWT + 文档 owner -> document=DELETING 且业务不可见
  |
  | WebClient POST /api/v1/documents/delete-vectors
  v
FastAPI 按 ownerUserId + kbId + docId 删除 Qdrant points
  |
  v
Spring Boot 删除 MinIO object -> document=DELETED
```

## 组件职责

### Spring Boot Backend

当前主后端，负责所有业务系统职责：

- 用户注册、登录、JWT 签发和 JWT 鉴权。
- 当前用户信息接口。
- 个人知识库创建、列表、详情、逻辑删除。
- 删除知识库前检查是否存在任何未进入 `DELETED` 的文档；`DELETING` / `DELETE_FAILED` 也会返回 `409 Conflict`，避免未完成清理记录失联。
- 文档上传与知识库删除锁定同一条 `knowledge_base` 行，串行化“活动文档检查”和“新文档插入”。
- 文档上传校验、文件去重、MinIO 上传、文档元数据落库。
- 文档列表、详情、索引状态查询和文档删除。
- 删除文档时先写 MySQL `DELETING` 且让文档业务不可见，再同步调用 Python 清理 Qdrant vectors，随后删除 MinIO object，最后写 `DELETED`。
- 删除外部资源失败时写 `DELETE_FAILED`，文档仍保持业务不可见，可再次调用删除接口重试。
- 创建不可变 `indexing_task` attempt，并由 `document.current_indexing_task_id` 标识当前执行窗口。
- MySQL 事务提交后向 RabbitMQ 投递索引任务。
- 使用 RabbitMQ listener 消费索引任务，手动 ack，消费者自身异常会进入 DLQ。
- 通过 WebClient 调用 Python 文档索引接口。
- 更新 `document.index_status`、`document.chunk_count`、`document.error_message`。
- 更新 `indexing_task.status`、`started_at`、`finished_at`、`error_message`。
- 只有 Python 返回 `INDEXED` 且 Qdrant 写入数量与 chunk 数量一致时，才写 `INDEXED` / `SUCCESS`。
- 当前不支持同一文档同时执行索引和删除，也不支持并发删除：`PENDING_INDEX`、`INDEXING`、`DELETING` 返回 `409004 DOCUMENT_BUSY`。
- RabbitMQ 投递失败时保留当前 task `PENDING`；重投器先按 `last_publish_attempt_at` 原子 claim 并节流，也可通过 `index-retry` 手动重投同一 attempt。
- 索引失败后的 `index-retry` 会创建新的 `MANUAL_RETRY` task，再 CAS 切换 current pointer；旧 FAILED task 永不复活。
- RUNNING 索引 task 超时后与 current document 在同一短事务内进入失败态；DELETING 超时按 `delete_generation` 条件更新。
- 对外提供知识库内向量检索入口，并在调用 Python 前校验当前用户拥有知识库。
- 检索返回前会用 MySQL 未删除文档再过滤一次 Qdrant 命中结果，防止历史残留 vectors 被返回。
- 对外提供最小同步 RAG 问答入口，复用检索结果后调用 Python 生成答案，并返回 citations/retrievedChunks。

### FastAPI AI Service

当前负责 AI 侧处理和 Qdrant 访问：

- 暴露 `POST /api/v1/documents/index`，供 Java 索引消费者调用。
- 根据 Java 传入的 bucket/object_key 从 MinIO 下载文件。
- 按扩展名解析 TXT、Markdown、PDF、DOCX。
- 将解析后的文档切分为带页码和字符偏移的 chunk。
- 默认使用本地 `BAAI/bge-small-zh-v1.5` 生成 embedding。
- 将 chunk、vector 和权限 metadata 写入 Qdrant。
- 暴露 `POST /api/v1/documents/delete-vectors`，供 Java 删除文档后清理 Qdrant points。
- 暴露 `POST /api/v1/retrieval/search`，供 Java 检索服务调用。
- 对 query 生成 embedding，并使用 Qdrant payload filter 限制 `ownerUserId + kbId`。
- 返回命中的 chunk、score、docId、pageNo、chunkIndex、charStart、charEnd、text。
- 暴露 `POST /api/v1/rag/generate`，供 Java 在完成权限校验和 chunk 过滤后调用。
- 通过 OpenAI-compatible Chat Completions 根据 Java 传入的上下文生成答案。

FastAPI 当前不负责登录态、完整用户权限判断、会话保存或流式输出。RAG 生成只消费 Java 已过滤后的上下文，不自行访问 MySQL 或任意知识库。

### MySQL

当前已落地使用的表：

- `user`：用户账号、密码哈希、状态。
- `knowledge_base`：个人知识库，当前只支持 owner 私有知识库。
- `document`：文档元数据、MinIO 位置、索引状态、chunk 数；通过 `active_checksum_sha256` 生成列只对未删除文档做同知识库 checksum 去重。
- `indexing_task`：文档索引任务状态。

SQL 中还创建了以下表，但当前 Java 代码尚未使用：

- `conversation`
- `chat_message`
- `answer_citation`
- `feedback`
- `model_call_log`

这些表是后续会话型 RAG、引用落库、反馈和模型调用记录的数据库基础，不属于当前已落地业务链路。

### RabbitMQ

当前承担文档索引异步队列职责：

- Exchange：`ekb.indexing.exchange`
- Queue：`ekb.indexing.tasks`
- Routing key：`indexing.task`
- Dead letter exchange：`ekb.indexing.dlx`
- Dead letter queue：`ekb.indexing.tasks.dlq`
- Dead letter routing key：`indexing.task.dead`
- 消息字段：`documentId`、`indexingTaskId`

Spring Boot 启动时通过 AMQP 声明 durable exchange、queue、DLX 和 DLQ。上传文档事务提交后，Spring Boot 向 RabbitMQ 发布 JSON 消息；消费者读取消息后调用 `IndexingService.processIndexingTask`。

当前 Python 调用失败会落库为 `FAILED` 并 ack 消息，不依赖队列自动反复重试。消息格式错误、消费者自身异常或未能正常落库的异常会 `basicReject(requeue=false)` 进入 DLQ。上传后如果 RabbitMQ 发布失败，`indexing_task` 保持 `PENDING`，由简单 PENDING 任务重投器再次投递，也允许调用 `/api/v1/documents/{documentId}/index-retry` 手动重投同一任务。

### Redis

当前承担三个职责：认证用户短 TTL 快照缓存、Refresh Token 会话存储，以及知识库访问缓存。Redis 不再承担文档索引异步队列。

认证用户缓存：

- Key：`ekb:auth:user:{userId}`
- 默认 TTL：300 秒，并附加 0～60 秒随机抖动。
- 缓存内容只包含认证 principal 需要的用户快照，不包含密码哈希。

JWT 过滤器优先读取 Redis 认证缓存；缓存未命中、Redis 异常或缓存内容不可解析时回源 MySQL。Redis 在这里不是权限事实源，只是减少每个认证请求访问 `user` 表的加速层。

Refresh Token 会话：

- Key：`ekb:auth:refresh:{sha256(refreshToken)}`
- 用户集合：`ekb:auth:user-refresh:{userId}`
- 默认 TTL：1209600 秒。
- Value：`userId`、`username`、`issuedAtEpochSecond`、`expiresAtEpochSecond`。

登录成功会创建 refresh token 会话；刷新 token 时会消费旧会话并签发新会话；退出登录会删除当前 refresh token 会话。当前不把 access token 全量写入 Redis，也不做 access token 黑名单。

知识库访问缓存：

- Key：`ekb:kb:meta:{kbId}`
- 默认 TTL：300 秒，并附加 0～60 秒随机抖动。
- Value：知识库 `id`、`ownerUserId`、名称、描述、可见性、状态、创建/更新时间和删除标记。

Java 在知识库详情、文档入口、检索入口和 RAG 入口复用同一套知识库访问校验。缓存命中后仍由 Java 比较 `ownerUserId` 和当前用户 id；如果 owner 不匹配，继续返回 not found 风格错误。缓存未命中或 Redis 不可用时回源 MySQL，并用 MySQL 完整行写入缓存。删除知识库事务提交后删除缓存。

### MinIO

当前作为上传文件对象存储：

- Java 上传文件到 MinIO。
- Java 自动确保 bucket 存在。
- MySQL `document` 表保存 `bucket` 和 `object_key`。
- Python 根据 `bucket` 和 `object_key` 从 MinIO 下载文件。

Java 不把文件字节通过 HTTP 转发给 Python。

### Qdrant

当前已经用于索引写入和检索：

- Python 依赖中包含 `qdrant-client`。
- 默认 Qdrant URL：`http://localhost:6333`。
- 默认 collection：`rag_chunks_v1`。
- Python 会按 embedding 实际 `vector_dim` 自动创建 collection。
- point id 由固定 namespace + `documentId + chunkIndex` 确定性生成，重复 upsert 会覆盖同一批 point。
- 写入前会按 `ownerUserId + kbId + docId` 删除旧 point，再 upsert 新 point，保证同文档重试索引幂等，并避免 chunk 数变少时旧 point 残留。
- 删除文档时会按 `ownerUserId + kbId + docId` 删除该文档所有 point。
- 检索时必须按 `ownerUserId + kbId` 过滤，避免跨用户或跨知识库召回。

Point payload 包含：

- 权限过滤字段：`ownerUserId`、`kbId`
- 业务定位字段：`docId`、`taskId`、`chunkId`
- 文件字段：`fileName`、`contentType`、`fileSize`、`bucket`、`objectKey`、`checksumSha256`
- 引用溯源字段：`pageNo`、`chunkIndex`、`charStart`、`charEnd`、`text`
- embedding 字段：`embeddingProvider`、`embeddingModel`
- 时间字段：`createdAt`

## 服务调用关系

### 前端或调用方到 Java

浏览器前端由 Spring Boot 通过 `/console/` 同源提供，调用方只访问 Spring Boot：

- 登录、注册、刷新 token 和退出登录不需要 JWT。
- 除登录、注册、刷新 token 和退出登录外，Java API 均要求 `Authorization: Bearer <accessToken>`。
- Java JWT 过滤器会解析 token 中的用户 ID，并通过 Redis 认证缓存或 MySQL 加载当前用户快照。
- 文档上传使用 `multipart/form-data`。
- Java 响应统一包裹在 `ApiResponse<T>`。
- 前端使用浏览器端路由组织登录、知识库工作台、文档与索引、向量检索和同步 RAG 问答页面。
- 前端保存 access token 和 refresh token；业务请求遇到 401 时会尝试刷新一次，退出登录时调用 Java 撤销当前 refresh token。
- 前端不直接调用 Python AI 服务，也不接触任何 LLM API Key。
- 当前前端不提供 SSE、历史会话或 Agent 页面。

### Java 到 RabbitMQ

上传文档时：

1. Java 读取文件并计算 SHA-256 checksum。
2. Java 在事务内先插入 `document`，通过 `(kb_id, active_checksum_sha256)` 唯一索引抢占 active 文档。
3. 抢占成功后写 MinIO object，并注册事务完成回调；事务明确 ROLLED_BACK 时按 objectKey 幂等清理。若 commit 结果为 UNKNOWN，则保留 object 并告警，避免误删其实已经提交的文档对象。
4. Java写入 `attempt_no=0, trigger_type=UPLOAD` 的 `indexing_task`，再回写 `document.current_indexing_task_id`。
5. 注册事务提交回调。
6. 事务提交后向 RabbitMQ exchange 发布只包含 `documentId` 和 `indexingTaskId` 的 JSON 消息。

这个顺序用于处理同一知识库内并发上传同内容文件：未抢占到 checksum 的请求会在写 MinIO 前返回 `409003 DOCUMENT_ALREADY_EXISTS`，避免留下重复 UUID object。

### Java 到 Python 文档索引

RabbitMQ 消费者拿到消息后：

1. 根据 `documentId`、`indexingTaskId` 查询 MySQL，校验 task/document 的 document、kb、owner 关联，并确认 task 是 current attempt。
2. 将任务标记为 `RUNNING`，文档标记为 `INDEXING`。
3. 通过 WebClient 调用 `POST http://localhost:8000/api/v1/documents/index`。
4. Python 将 chunk/vector/payload 写入 Qdrant。
5. Python 返回 `INDEXED` 和索引统计。
6. Java 校验响应回显的 `task_id/document_id` 以及 `indexed_chunk_count == chunk_count`。
7. Java 更新 `document.chunk_count`，并写入 `INDEXED` / `SUCCESS`。
8. 如果调用异常或响应不符合契约，Java 将 `document.index_status` 标记为 `INDEX_FAILED`，将 `indexing_task.status` 标记为 `FAILED`。
9. 如果文档在索引前已经删除，Java 跳过任务并标记任务失败。
10. 如果旧 worker 在超时或人工重试后迟到返回，Java 通过 immutable attempt + current pointer fencing 避免覆盖新的 MySQL 状态；这不等于撤回已经发生的 Qdrant 写入。
11. 业务处理正常返回后手动 ack；消费者自身异常或非法消息进入 DLQ。

### Java 到 Python 向量检索

调用检索接口时：

1. 客户端调用 `POST /api/v1/knowledge-bases/{kbId}/retrieval/search`。
2. Java JWT 过滤器解析当前用户。
3. Java 查询 `knowledge_base`，确认 `id=kbId`、`owner_user_id=currentUserId`、`is_deleted=0`。
4. 校验失败时返回 `404001 Knowledge base not found`，不调用 Python。
5. 校验成功后，Java 调用 `POST http://localhost:8000/api/v1/retrieval/search`。
6. Python 对 query 做 embedding。
7. Python 调 Qdrant，filter 必须包含 `ownerUserId=currentUserId` 和 `kbId=kbId`。
8. Python 返回 topK chunk。
9. Java 根据返回的 `docId` 查询 MySQL，只保留当前用户、当前知识库、未删除且 `INDEXED` 文档的命中。
10. Java 将过滤后的结果返回为统一 `ApiResponse`。

### Java 到 Python RAG 生成

调用 RAG 问答接口时：

1. 客户端调用 `POST /api/v1/knowledge-bases/{kbId}/rag/ask`。
2. Java JWT 过滤器解析当前用户。
3. Java 复用检索服务：先校验知识库 owner，再调用 Python 检索，并用 MySQL 二次过滤 chunk。
4. 如果没有可用 chunk，Java 直接返回无上下文答案，不调用 LLM。
5. 如果有可用 chunk，Java 将 question 和 chunk context 调用 `POST http://localhost:8000/api/v1/rag/generate`。
6. Python 按 OpenAI-compatible Chat Completions 协议调用外部 LLM。
7. Java 返回 answer、citations 和 retrievedChunks。

当前 RAG 问答不写入 conversation、chat_message 或 answer_citation，也不提供 SSE。

### Java 到 Python 文档 vectors 删除

删除文档时：

1. 客户端调用 `DELETE /api/v1/documents/{documentId}`。
2. Java 查询 `document`，确认 `id=documentId`、`owner_user_id=currentUserId`。
3. 如果文档处于 `PENDING_INDEX`、`INDEXING`、`DELETING`，Java 返回 `409004 DOCUMENT_BUSY`。
4. Java CAS 递增 `delete_generation`，将 `document.is_deleted` 更新为 1，并将 `index_status` 更新为 `DELETING`。
5. Java 调用 `POST http://localhost:8000/api/v1/documents/delete-vectors`。
6. Python 按 `ownerUserId + kbId + docId` 删除 Qdrant points；collection 不存在或 point 不存在视为成功。
7. Java 删除 MinIO object；object 不存在视为成功。
8. Java 仅在状态仍为 `DELETING` 且 generation 匹配时将 `document.index_status` 更新为 `DELETED`。

当前没有分布式事务；如果 Qdrant 或 MinIO 清理失败，Java 只对本 generation 写 `DELETE_FAILED`，并保留 `is_deleted=1`。当前不引入补偿任务表或 outbox，重试方式是再次调用删除接口并认领新 generation。

### Python 到 MinIO

Python 根据 Java DTO 中的 `bucket` 和 `object_key` 下载文件。Python 不直接访问 MySQL，也不自行判断用户权限。

## 当前状态流转

### document.index_status

当前代码实际会写入：

- `PENDING_INDEX`：文档和索引任务已落库，等待 RabbitMQ 消费。
- `INDEXING`：Java 消费索引任务并正在调用 Python。
- `INDEXED`：Python 已完成 Qdrant 写入，Java 已确认写入数量。
- `INDEX_FAILED`：Python 调用失败、响应不符合契约或索引超时。
- `DELETING`：删除流程已开始，文档已对业务不可见。
- `DELETED`：Qdrant vectors 和 MinIO object 清理完成。
- `DELETE_FAILED`：删除 Qdrant vectors 或 MinIO object 失败，文档仍保持业务不可见，可再次 DELETE 重试。

### indexing_task.status

当前代码实际会写入：

- `PENDING`：新 attempt 等待消费，或同一 PENDING attempt 等待传输重投。
- `RUNNING`：Java 消费任务并开始调用 Python。
- `SUCCESS`：Python 已完成 Qdrant 写入，Java 已确认写入数量。
- `FAILED`：索引调用失败、响应不符合契约、文档删除或索引超时。

task 状态单向流转，`FAILED` 不回到 `PENDING`。失败后的业务重试创建后继 attempt；`document.current_indexing_task_id` 决定哪个 task 可以写 document 状态。

### Python 索引响应状态

Python schema 允许：

- `ACCEPTED`：schema 保留状态，当前主流程不会返回它。
- `PARSED`：schema 保留状态，当前主流程不会返回它。
- `CHUNKED`：下载、解析、切分完成，但没有 embedding 和 Qdrant 数据，通常是 `EMBEDDING_PROVIDER=none`。
- `EMBEDDED`：schema 保留状态，表示已生成 embedding 但未写入 Qdrant；当前主流程不会返回它。
- `INDEXED`：下载、解析、切分、embedding、Qdrant 写入完成。

Java 只接受 `INDEXED` 作为索引成功。

## 当前未落地架构

以下能力尚未进入运行链路：

- SSE 流式回答。
- 会话、消息、引用、反馈业务 API。
- 复杂 no-answer 判断。
- query rewrite、hybrid search、rerank、context compression。
- 检索评测。
- Agent 工作流。
- Docker Compose 一键部署。
- 完整 RBAC 或多租户。
