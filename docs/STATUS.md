# 项目状态

更新时间：2026-06-29

本文档只记录当前仓库状态，不记录未来设想为已完成。

## 已完成

### 基础工程

- 创建 Spring Boot 后端项目 `enterprise-rag-backend`。
- 创建 FastAPI AI 服务项目 `rag-ai-service`。
- 创建 MySQL V1 schema：`enterprise-rag-backend/src/main/resources/db/migration/V1__init_schema.sql`。
- Java 后端统一响应结构 `ApiResponse<T>`。
- Java 后端统一分页结构 `PageResponse<T>`。
- Java 后端统一错误码 `ErrorCode` 和全局异常处理。
- Java 后端 requestId 过滤器和响应头返回。

### 用户与鉴权

- 用户注册：`POST /api/v1/auth/register`
- 用户登录：`POST /api/v1/auth/login`
- Refresh Token 刷新：`POST /api/v1/auth/refresh`
- 退出登录：`POST /api/v1/auth/logout`
- 当前用户：`GET /api/v1/users/me`
- BCrypt 密码哈希。
- Access Token 使用 JWT 签发、解析、过期校验，默认有效期 1800 秒。
- Refresh Token 使用高熵随机字符串，服务端状态保存在 Redis，默认有效期 1209600 秒。
- 登录成功会签发 access token 和 refresh token；刷新 token 时会撤销旧 refresh token 并签发新 refresh token。
- 退出登录会撤销当前 refresh token；当前 access token 不进入 Redis 黑名单，等待自然过期。
- Spring Security 无状态鉴权。
- 除登录、注册、刷新 token、退出登录外，Java API 默认需要 JWT。
- 已新增 Redis 认证用户快照缓存：登录成功会预热 `ekb:auth:user:{userId}`，JWT 过滤器优先读取 Redis，未命中或 Redis 异常时回源 MySQL。
- 认证缓存只保存 `id`、`username`、`nickname`、`email`、`status`、`isDeleted`，不保存密码哈希；默认 TTL 为 300 秒并带 0～60 秒随机抖动。
- 认证缓存不作为权限事实源，MySQL 仍是用户状态最终来源；Redis 故障时认证链路降级为原有 MySQL 查询。

### 知识库

- 创建个人知识库。
- 查询当前用户知识库列表。
- 查询当前用户知识库详情。
- 逻辑删除知识库。
- V1 权限模型：`owner_user_id` 私有归属。
- 删除知识库前会检查是否存在未删除文档；如果存在，返回 `409 Conflict`，避免知识库删除后文档和 vectors 残留在不可解释状态。
- 删除知识库时将名称追加 `#deleted#id`，释放原名称唯一约束。
- 已新增 Redis 知识库访问缓存：详情、文档入口、检索入口和 RAG 复用同一套 owner 校验服务，命中缓存时不再访问 `knowledge_base` 表。
- 知识库访问缓存不缓存“某用户无权访问”的负结果；如果缓存命中但 owner 不匹配，仍返回 `KNOWLEDGE_BASE_NOT_FOUND`。

### 文档与对象存储

- 支持上传 PDF、DOCX、TXT、MD、Markdown。
- 文件大小限制：20MB。
- Spring Boot 上传文件到 MinIO。
- Spring Boot 自动确保 MinIO bucket 存在。
- MySQL `document` 保存文件元数据、bucket、object_key、checksum。
- 同一知识库内对未删除文档基于 SHA-256 去重；已删除历史记录保留原 checksum，但不再参与唯一冲突。
- 上传流程先用 MySQL active checksum 唯一索引抢占文档，再写 MinIO object，避免同一知识库内并发上传同内容文件时失败请求留下重复 MinIO 对象。
- 文档列表、文档详情、索引状态查询。
- 文档删除采用 MySQL 状态驱动的最终一致性方案：先将文档置为 `DELETING` 且业务不可见，再删除 Qdrant vectors、删除 MinIO object，最后置为 `DELETED`。
- 删除 Qdrant vectors 或 MinIO object 失败时，文档保持业务不可见并进入 `DELETE_FAILED`，可再次调用删除接口重试。
- 文档处于 `PENDING_INDEX`、`INDEXING` 时，删除接口返回 `409004 DOCUMENT_BUSY`，V1 不支持同一文档同时索引和删除。
- `document.index_status` 当前只保留当前流程实际使用的 7 个状态，不保留未写入的预留上传状态。

### RabbitMQ 异步索引

- 上传文档后创建 `indexing_task`。
- MySQL 事务提交后投递 RabbitMQ。
- RabbitMQ exchange：`ekb.indexing.exchange`。
- RabbitMQ queue：`ekb.indexing.tasks`。
- RabbitMQ DLQ：`ekb.indexing.tasks.dlq`。
- Java 使用 RabbitMQ listener 消费索引任务，手动 ack。
- 消费者根据 RabbitMQ 消息中的 `documentId`、`indexingTaskId` 查询 MySQL。
- 消费者会跳过已删除文档；V1 通过删除接口的 409 busy 规则避免同一文档同时索引和删除。
- Java 通过 WebClient 调用 Python 索引接口。
- Spring Boot 启动时会声明 durable exchange、queue、DLX 和 DLQ。
- 索引成功时更新 `document.index_status=INDEXED`、`indexing_task.status=SUCCESS`。
- 索引失败时更新 `document.index_status=INDEX_FAILED`、`indexing_task.status=FAILED`。
- RabbitMQ 投递失败时不再把任务标记失败，保留 `indexing_task.status=PENDING`，由简单 PENDING 任务重投器继续投递。
- RabbitMQ 消息格式错误、消费者自身异常或未能正常落库的异常会进入 DLQ；Python 调用失败属于业务失败，会落库为 `FAILED` 并 ack。
- 已提供索引失败后的人工重试入口：`POST /api/v1/documents/{documentId}/index-retry`。
- `index-retry` 同时支持 `document=PENDING_INDEX`、`task=PENDING` 的手动重投场景，用于恢复 RabbitMQ 发布失败后尚未真正执行索引的任务；该路径不增加 `retry_count`。
- 已提供简单超时标记：长时间停留在 `INDEXING`、`DELETING` 的文档会被标记为失败态，便于人工重试。

### Redis 认证缓存

- 缓存 key 前缀：`ekb:auth:user:`。
- 配置项：`app.security.auth-cache.enabled`、`key-prefix`、`ttl-seconds`、`ttl-jitter-seconds`。
- 缓存命中时，JWT 过滤器不再为每个认证请求访问 `user` 表。
- 缓存未命中、缓存反序列化失败或 Redis 不可用时，回源 MySQL 并在用户有效时重新写入缓存。
- 当前没有账号禁用/删除管理接口；如果后续增加这类接口，必须在状态变更时删除对应认证缓存 key。

### Redis Refresh Token 会话

- Refresh Token key 前缀：`ekb:auth:refresh:`，实际 key 使用 refresh token 的 SHA-256 摘要，不保存明文 refresh token。
- 用户 refresh token 集合 key 前缀：`ekb:auth:user-refresh:`。
- 配置项：`app.security.refresh-token.key-prefix`、`user-sessions-key-prefix`、`expires-in-seconds`、`token-bytes`。
- 登录成功写入 refresh token 会话；刷新 token 时消费旧会话并轮换新会话；退出登录删除当前 refresh token 会话。
- 当前未实现退出所有设备、修改密码强制失效、封禁强制失效或 access token 黑名单。

### Redis 知识库访问缓存

- 缓存 key 前缀：`ekb:kb:meta:`。
- 配置项：`app.knowledge.access-cache.enabled`、`key-prefix`、`ttl-seconds`、`ttl-jitter-seconds`。
- 缓存内容包含知识库 `id`、`ownerUserId`、`name`、`description`、`visibility`、`status`、`createdAt`、`updatedAt`、`isDeleted`。
- 该缓存只服务于知识库 owner 权限校验和详情读取，不缓存知识库列表、文档列表或检索结果。
- 缓存未命中、缓存反序列化失败或 Redis 不可用时回源 MySQL。
- 首次读取知识库访问快照时写入缓存；删除知识库事务提交后删除缓存。

### Python 文档索引

- FastAPI 应用入口：`app.main:app`。
- 已实现接口：`POST /api/v1/documents/index`。
- Python 从 MinIO 下载 Java 上传的文件。
- TXT、Markdown、PDF、DOCX loader 已实现。
- 文档解析为统一 `ParsedDocument`。
- 文本切分为带 page_no、chunk_index、char_start、char_end 的 chunk。
- 默认使用本地 `BAAI/bge-small-zh-v1.5` embedding 模型。
- 写入 Qdrant collection：`rag_chunks_v1`。
- Qdrant point id 由固定 namespace + `documentId + chunkIndex` 确定性生成，重复 upsert 会覆盖同一批 point。
- 写入前按 `ownerUserId + kbId + docId` 删除旧 point，避免重试后 chunk 数变少时残留旧高序号 chunk。
- Qdrant collection 和 `ownerUserId/kbId/docId` payload index 初始化已做进程内加锁和“已存在”幂等处理，避免首次并发索引时两个任务同时创建 schema 导致其中一个任务失败。
- 已实现内部接口 `POST /api/v1/documents/delete-vectors`，支持按 `ownerUserId + kbId + docId` 删除文档 vectors。
- Qdrant point payload 包含 `ownerUserId`、`kbId`、`docId`、`chunkId`、`fileName`、`pageNo`、`chunkIndex`、`charStart`、`charEnd`、`text` 等检索和引用元数据。
- 返回 `chunk_count`、`embedded_chunk_count`、`indexed_chunk_count`、`vector_dim`、`vector_store`、`vector_collection` 等统计。
- Java 收到 `INDEXED` 且写入数量校验通过后，将 `document.index_status` 更新为 `INDEXED`，将 `indexing_task.status` 更新为 `SUCCESS`。

### 知识库内向量检索

- Python 已实现 `POST /api/v1/retrieval/search`。
- Java 已实现 `POST /api/v1/knowledge-bases/{kbId}/retrieval/search`。
- Java 检索前校验当前用户拥有知识库。
- Python 对 query 做本地 embedding。
- Python 使用 Qdrant filter 限制 `ownerUserId + kbId`。
- Java 返回前会再按 MySQL 未删除文档过滤 Qdrant 命中，避免删除残留 vectors 被返回。
- Java 检索前会先判断当前知识库是否存在已索引文档；没有 `INDEXED` 文档时直接返回空结果，不调用 Python embedding/Qdrant。
- Java 返回 topK chunk、score、docId、pageNo、chunkIndex、charStart、charEnd、text。
- 当前检索接口只返回 chunk，不调用 LLM，不生成最终答案。

### 最小 RAG 问答

- Java 已实现 `POST /api/v1/knowledge-bases/{kbId}/rag/ask`。
- RAG 问答复用现有检索服务，因此仍由 Java 先校验知识库 owner，再由 Python/Qdrant 按 `ownerUserId + kbId` 过滤召回。
- Java 将过滤后的 chunk 传给 Python 内部接口 `POST /api/v1/rag/generate`。
- Python 已实现 OpenAI-compatible Chat Completions 调用，配置来自 `LLM_PROVIDER`、`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`；本地存在 `DEEPSEEK_API_KEY` 时会自动使用 DeepSeek 默认配置。
- RAG 响应返回 answer、citations 和 retrievedChunks；citations 包含检索 score，LLM prompt 要求用 `[片段 n]` 标记答案依据。
- 如果知识库没有可用已索引 chunk，Java 直接返回无上下文答案，不调用 LLM。
- 当前 RAG 是同步接口，不支持 SSE 流式输出，不落库会话、消息或引用。

### 前端应用

- Spring Boot 已提供同源静态前端：`GET /console/`。
- 前端使用浏览器端路由组织登录页、注册页、登录后主布局、知识库工作台、文档与索引、向量检索和同步 RAG 问答页面。
- 前端覆盖注册登录、退出登录、知识库创建/删除和选择、文档上传、索引状态自动轮询、索引失败重试、文档删除、向量检索和同步 RAG 问答。
- 前端信息架构按“知识库 -> 文档索引 -> 向量检索 -> 同步问答”组织，便于演示 Java 权限边界、RabbitMQ 异步索引、Qdrant 检索和删除清理闭环。
- 前端和 Java API 同源访问，不需要额外前端构建工具或 CORS 配置。
- 前端只调用 Java 已实现 API，不直接调用 Python，不接触任何 DeepSeek/OpenAI API Key；当前不提供 SSE、历史会话或 Agent 页面。

### 本地 embedding 模型

- 新增下载脚本：`scripts/download_embedding_model.py`。
- 默认模型目录：`.models/bge-small-zh-v1.5`。
- `.gitignore` 已忽略 `.models/` 和 Python 缓存。
- Python 默认 `EMBEDDING_PROVIDER=local`。
- 支持临时设置 `EMBEDDING_PROVIDER=none` 跳过 embedding，但 Java 不会把 `CHUNKED` 当作索引成功。

### 权限与 RAG 测试材料

- 已有 `scripts/test_retrieval_permission.sh`。
- 已有 `scripts/test_document_reupload_delete.sh`。
- 已有 `scripts/test_rag_ask.sh`。
- 已有 `scripts/test_rag_empty_kb.sh`。
- 已有 `scripts/test_rag_permission.sh`。
- 已有 `tmp/retrieval-permission-test/*.txt` 测试文档。
- 检索权限脚本会创建 Alice/Bob 两个用户和知识库，验证不同用户只能检索自己的知识库内容，并验证删除文档后不会再召回该文档 chunk。
- 重复上传删除脚本会验证同一内容文档删除后可再次上传，并且第二次删除不会与历史 deleted 行的 checksum 唯一约束冲突。
- RAG 脚本覆盖正常问答、空知识库兜底响应和 Alice/Bob RAG 权限隔离；`test_rag_permission.sh` 已完成端到端验证。

### RAG 评测基线

- 新增最小 RAG 评测资产：`eval/rag/eval_config.json`、`eval/rag/questions.jsonl` 和 `eval/rag/documents/*.txt`。
- 新增评测执行脚本：`scripts/run_rag_eval.py`。
- 当前评测集包含 50 条问题，覆盖单段事实型、多段信息组合型、表述改写型、无答案型和权限隔离型。
- 评测脚本会创建临时 Alice/Bob 用户和知识库，上传固定评测文档，等待索引完成，然后调用 Java retrieval/RAG API。
- 输出目录为 `eval/rag/results/{run_id}/`，包含 `run_config.json`、`results.jsonl`、`summary.json` 和 `bad_cases.md`。
- 当前指标包含文档级 Recall@K、MRR、Evidence Hit Rate、答案关键事实命中、引用正确性、无答案识别和 forbidden terms 权限泄露检查。
- 评测说明文档：`docs/RAG_EVALUATION.md`。
- 已记录 2026-06-28 retrieval-only baseline：Recall@5=1.0、MRR=0.8871、Evidence Hit Rate=0.95、权限泄露次数为 0。
- 已记录 2026-06-29 完整 RAG baseline：Recall@5=1.0、Answer Correct Rate=0.925、Citation Correct Rate=1.0；3 个 permission bad case 属于问题回显型 false positive，未发现真实跨用户检索泄露。

### 可靠性验证矩阵

- 新增可靠性矩阵文档：`docs/RELIABILITY_MATRIX.md`。
- 新增 RabbitMQ 重复消息幂等验证脚本：`scripts/test_indexing_duplicate_message.sh`。
- 新增 RabbitMQ 发布失败后 PENDING 手动重投验证脚本：`scripts/test_pending_index_republish.sh`。
- 新增并发上传与 Qdrant schema 初始化幂等验证脚本：`scripts/test_concurrent_document_upload.sh`。
- 新增同一知识库并发同内容上传去重验证脚本：`scripts/test_concurrent_duplicate_upload.sh`。
- 新增删除 busy 冲突验证脚本：`scripts/test_document_delete_busy.sh`。
- 可靠性矩阵覆盖 RabbitMQ 重复消息、RabbitMQ 发布失败后的 PENDING 手动重投、FastAPI 不可用、人工重试、旧 worker 迟到返回、删除 busy、删除外部资源失败、同内容删除重传、并发同内容上传去重、Qdrant 删除残留防线、并发 Qdrant schema 初始化和权限隔离。
- 当前已经有脚本覆盖索引失败人工重试、同内容文档重传删除、检索权限隔离、RAG 权限隔离和空知识库短路；部分外部依赖故障注入仍作为人工演练或设计保证记录。
- 2026-06-29 已手动验证通过：同知识库并发同内容上传去重、Redis XADD 失败后 PENDING 手动重投、Redis Stream 重复消息幂等。
- 2026-07-09 已完成索引队列代码层替换：RabbitMQ exchange/queue/DLQ、手动 ack listener、PENDING 重投器已落地；RabbitMQ 故障脚本仍需在本地依赖启动后重新验证。

## 正在进行

当前 Redis 使用已收敛为认证用户缓存、Redis Refresh Token 会话和知识库访问缓存；文档索引异步队列已切换为 RabbitMQ。后续如继续实现 Redis 场景，应优先考虑 RAG 限流，不应扩展为全量业务缓存。

上传、索引、删除一致性简化阶段和最小同步 RAG 阶段已经落地到代码。当前实现收敛为 MySQL 状态、`indexing_task`、幂等重试、简单超时标记、Java 编排检索和 Python 生成；下一步应先提交稳定 checkpoint，而不是继续扩展新能力。

## 尚未完成

- SSE 流式回答。
- 会话、消息、引用、反馈 API。
- 复杂 no-answer 判断。
- query rewrite、hybrid search、rerank、context compression。
- 基于评测结果的 chunk size、topK、相似度阈值对照实验。
- 人工或 LLM judge 形式的答案正确性、忠实度细粒度评分。
- RabbitMQ 发布失败、旧 worker 迟到返回、Qdrant/MinIO 删除失败的自动化故障注入脚本。
- Agent 工作流。
- Docker Compose 一键部署。
- 完整 RBAC。
- 多租户 tenant。
- 退出所有设备。
- 修改密码、封禁用户或账号异常时的 tokenVersion 强制失效。
- Access Token 黑名单。
- 模型调用日志写入。
- token 成本统计。
- 生产级监控、限流、熔断。

## 已知问题

### RabbitMQ ack 策略

当前 `IndexingQueueConsumer` 在调用 `indexingService.processIndexingTask(...)` 正常返回后会手动 ack 消息。

而 `processIndexingTask` 内部会捕获 Python 调用异常并将任务标记为 `INDEX_FAILED` / `FAILED`，不会继续向外抛出。因此，如果 Python 调用失败，RabbitMQ 消息仍会被 ack，不依赖队列自动反复重试。

这符合当前“失败落库可见 + 人工重试”的 V1 行为。失败任务不会依赖 MQ 自动重试，用户可通过 `POST /api/v1/documents/{documentId}/index-retry` 重新置为 `PENDING` 并投递。消息格式错误、消费者自身异常或未能正常落库的异常会被 `basicReject(requeue=false)` 投递到 DLQ。

### Qdrant vectors 或 MinIO 删除失败不做后台补偿

当前删除文档会先把 MySQL `document` 置为 `DELETING` 且 `is_deleted=1`，再同步调用 Python 删除 Qdrant vectors，随后删除 MinIO object，最后置为 `DELETED`。

如果 Qdrant 或 MinIO 删除失败，Java 会把文档置为 `DELETE_FAILED` 并保留 `is_deleted=1`，因此文档不会重新出现在列表或检索结果里。V1 不引入 outbox、补偿任务表或通用一致性协调器，重试方式是再次调用删除接口。

### SQL 初始化脚本会删除数据库

`V1__init_schema.sql` 第一行是 `Drop database enterprise_knowledge_hub;`。这适合本地重置演示环境，但不适合保留数据的开发库或任何生产环境。

已有本地库从旧版本升级时，应执行 `V2__fix_document_active_checksum_index.sql`，将 `document` 的 checksum 唯一约束修正为只约束未删除文档，避免同内容文件“删除 -> 重传 -> 再删除”时与历史 deleted 行冲突。

### SQL 中有后续表但没有业务 API

Schema 已创建 `conversation`、`chat_message`、`answer_citation`、`feedback`、`model_call_log`，但当前 Java 代码没有对应 Controller/Service/Mapper。

### 本地模型依赖外部下载

默认使用本地 `BAAI/bge-small-zh-v1.5`。如果没有运行下载脚本或没有安装 `sentence-transformers`，Python 文档索引和检索都会失败。

### RAG 问答依赖外部 LLM 配置

RAG 生成接口需要可用 LLM 配置。可以显式设置 `LLM_PROVIDER=openai-compatible`、`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`；如果本地存在 `DEEPSEEK_API_KEY`，Python 会自动使用 `https://api.deepseek.com/v1` 和 `deepseek-chat`。两类配置都不存在时，Python 会返回 503，Java 对外表现为 AI service unavailable。

### Java 与 Python 超时仍是粗粒度配置

当前 Java WebClient response timeout 默认为 180 秒，适合本地首次加载模型，但没有针对索引和检索接口分别配置。

## 下一步最优先任务

最小 RAG 阶段完成后，下一步建议：

1. 提交一版稳定 checkpoint，边界是文档上传、异步索引、向量检索、删除清理和最小同步 RAG。
2. 面试演示优先使用现有脚本和真实业务接口验证，不恢复 health 接口。
3. 在同步 RAG 稳定前，不启动 SSE、Agent、复杂评测或多轮会话落库。
