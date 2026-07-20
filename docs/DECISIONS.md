# 技术决策记录

本文档记录已经确认并体现在当前代码中的关键技术决策。讨论过但没有落地的想法不写成最终决策。

## D01：Spring Boot 作为业务系统主体

决策：主业务后端使用 Java 17 + Spring Boot 3.3.5。

原因：

- 项目面向 Java 后端 / AI 工程化求职，必须体现 Java 后端工程能力。
- 用户、鉴权、权限、文档、任务状态、对象存储编排更适合放在 Spring Boot。
- Java 层更适合维护 MySQL 业务一致性和对外 API 契约。

放弃方案：

- 放弃纯 Python 全栈业务系统。原因是无法突出 Java 后端能力，也容易退化成 RAG demo。

## D02：FastAPI 作为 AI 能力服务

决策：文档解析、文本切分、本地 embedding、Qdrant 写入和向量检索放在 Python FastAPI 服务。

原因：

- Python 生态更适合文档解析、embedding 和向量库 SDK。
- FastAPI 通过 HTTP DTO 与 Java 解耦，便于后续替换模型或 pipeline。
- Java 不需要理解 sentence-transformers、PyMuPDF、Qdrant client 等 AI 侧细节。

放弃方案：

- 放弃 Java 直接实现当前 AI pipeline。原因是本地 embedding、PDF/Word 解析和未来 RAG 工具生态在 Python 中更成熟。
- 放弃 Java 直接调用 Python 脚本。原因是进程管理、错误处理、部署边界都不清晰。

## D03：Java 与 Python 通过 HTTP 通信

决策：Java 使用 WebClient 调用 FastAPI HTTP 接口。

当前接口：

- `POST http://localhost:8000/api/v1/documents/index`
- `POST http://localhost:8000/api/v1/documents/delete-vectors`
- `POST http://localhost:8000/api/v1/retrieval/search`
- `POST http://localhost:8000/api/v1/rag/generate`

原因：

- HTTP 是简单明确的服务边界。
- DTO 契约清晰，易测试、易替换、易部署。
- 避免 Java 与 Python 运行时强耦合。

放弃方案：

- 放弃本地进程调用 Python。
- 当前未采用 gRPC。原因是当前接口数量少，HTTP 更轻量。

## D04：RabbitMQ 作为异步索引队列

决策：上传文档后通过 RabbitMQ 异步触发索引。

当前配置：

- Exchange：`ekb.indexing.exchange`
- Queue：`ekb.indexing.tasks`
- Routing key：`indexing.task`
- Dead letter exchange：`ekb.indexing.dlx`
- Dead letter queue：`ekb.indexing.tasks.dlq`
- Dead letter routing key：`indexing.task.dead`

原因：

- 文档上传和索引处理之间需要真实异步边界，不能依赖 Spring 内存事件。
- RabbitMQ 的 exchange、queue、manual ack、DLQ 语义比 Redis Stream 更适合展示消息队列工程能力。
- Redis 继续保留认证缓存、Refresh Token 会话和知识库访问缓存职责，避免同一个中间件同时承担过多角色。
- MySQL 仍然是任务事实来源，RabbitMQ 只做任务触发，不承载完整业务状态。

放弃方案：

- 放弃 Spring 内部事件作为正式异步方案。原因是内存事件不适合多实例和任务恢复。
- 不继续使用 Redis Stream 作为索引队列。原因是下一阶段要求更清晰地展示 ack、DLQ、失败可见和消息队列边界。
- 暂不使用 Kafka。原因是当前只有一个索引任务队列，RabbitMQ 已能覆盖需求，Kafka 会显著增加本地部署和解释成本。

## D05：RabbitMQ 消息只保存 ID

决策：RabbitMQ 消息只保存 `documentId` 和 `indexingTaskId`。

原因：

- 文件位置、归属、状态以 MySQL 为准。
- 避免 MQ 消息体和数据库记录不一致。
- 消费者处理时重新查 MySQL，可以拿到最新状态。

放弃方案：

- 放弃把 bucket、objectKey、fileName、checksum 全部复制到 RabbitMQ 消息体。原因是消息膨胀且容易和数据库漂移。

## D06：文件通过 MinIO 在 Java 和 Python 之间传递

决策：Java 上传文件到 MinIO，Python 从 MinIO 下载文件。

原因：

- 避免 Java 将文件字节直接 HTTP 转发给 Python。
- 更接近真实文档处理系统：worker 从对象存储拉取任务文件。
- MySQL 只保存对象位置和元数据，不保存文件正文。

放弃方案：

- 放弃在 MySQL 中保存文档原文或文件二进制。
- 放弃 Java 调 Python 时携带完整文件内容。

## D07：当前权限模型采用 owner 私有模型

决策：当前不做完整 RBAC，只做个人知识库 owner 权限。

当前约束：

- `knowledge_base.owner_user_id`
- `document.owner_user_id`
- 查询知识库和文档时都按当前用户过滤。
- 检索前 Java 必须确认当前用户拥有知识库。
- 访问其他用户资源时返回 not found 风格错误，避免泄露资源存在性。

原因：

- 当前阶段需要真实权限边界，但不应过早引入角色、团队、多租户复杂度。
- owner 模型足以支撑个人项目演示中的权限隔离。

放弃方案：

- 暂不实现完整 RBAC。
- 暂不实现 tenant/team 权限。

## D08：默认使用本地 embedding 模型

决策：Python 默认使用本地 `BAAI/bge-small-zh-v1.5`。

原因：

- 避免当前阶段依赖线上 embedding API。
- 模型较小，适合 Mac 本地 CPU 开发。
- 输出 512 维向量，Qdrant collection 设计清晰。
- 本地模型可以通过 Docker volume 挂载到容器。

放弃方案：

- 暂不接入 OpenAI-compatible embedding API。原因是当前没有实现 provider，保留假配置会误导使用者。
- 暂不默认使用 `bge-m3`。原因是模型更重，本地开发成本更高。

## D09：当前不恢复 health 接口

决策：删除早期用于连通性验证的 health 端点。

原因：

- 当前连接应该通过真实业务链路验证。
- 保留 health 端点会让文档和测试路径停留在早期阶段。
- 演示应该通过注册、登录、上传文档、异步索引、检索体现工程闭环。

放弃方案：

- 放弃继续维护 `/health`、`/api/v1/health`、`/api/v1/health/ai`。

## D10：只有 Qdrant 写入成功后才标记 SUCCESS

决策：Python 必须返回 `INDEXED`，并且 `indexed_chunk_count` 与 `chunk_count` 一致后，Java 才写 `indexing_task.status=SUCCESS` 和 `document.index_status=INDEXED`。

原因：

- embedding 生成成功不等于可检索索引完成。
- Qdrant upsert 成功后，后续检索才有真实数据源。
- Java 仍然是业务状态唯一维护者，Python 只通过 DTO 汇报 AI/向量库处理结果。

放弃方案：

- 放弃在仅完成解析、切分或 embedding 统计后把索引任务标记成成功。

## D11：不保留未实现占位文件

决策：不保留只有 docstring 的未实现 API、service、DTO、retriever、generator、vector_store 占位文件。

原因：

- 空文件会让代码读者误以为功能存在。
- 面试项目中“假功能”容易被追问穿。
- 当前仓库应该让文件结构反映真实进度。

放弃方案：

- 放弃用空文件提前铺满未来目录。

## D12：先做向量检索，再做 RAG 问答

决策：先落地 retrieval/search 接口，只返回 Qdrant 命中的 chunk；在检索、权限隔离、删除清理稳定后，再实现最小同步 RAG 问答。

原因：

- 检索是 RAG 的基础能力，必须先能独立验证 query embedding、Qdrant filter、权限隔离和引用元数据。
- 先做检索接口可以避免把 LLM 回答质量问题和底层检索问题混在一起排查。
- 这一步能清楚展示 Java 调 Python、Python 调 Qdrant 的服务拆分。

放弃方案：

- 放弃直接从索引跳到复杂 RAG 问答。原因是风险太大，定位问题困难，也容易写成不可解释的 demo。

## D13：检索权限采用 Java 校验 + Qdrant filter 双层边界

决策：Java 检索入口先校验知识库 owner，Python 检索时再用 `ownerUserId + kbId` 做 Qdrant filter；Java 返回前再按 MySQL 未删除文档过滤一次命中结果。

原因：

- Java 是业务权限主边界，不能让 Python 接收用户随意传入的 `kbId` 后直接查向量库。
- Qdrant filter 是数据召回边界，不能只依赖 Java 事后过滤。
- MySQL 未删除文档过滤是删除一致性的防线，避免 Qdrant 历史残留 vectors 被返回。
- 双层边界能避免跨用户、跨知识库召回 chunk。

放弃方案：

- 放弃让客户端直接调用 Python 检索接口。
- 放弃 Python 无 filter 全局检索后再由 Java 过滤。原因是会带来越权召回和性能浪费。

## D14：当前失败任务先落库可见，不做自动重试

决策：索引失败时更新 `document.index_status=INDEX_FAILED` 和 `indexing_task.status=FAILED`，RabbitMQ 消息被 ack。

原因：

- 当前阶段优先让失败状态对用户和调试者可见。
- 当前已提供人工重试入口：失败文档进入新执行窗口时创建新的 PENDING task，旧 FAILED task 保持不可变终态，再复用 RabbitMQ 投递边界。
- 如果任务还停留在 `document=PENDING_INDEX`、`indexing_task=PENDING`，说明它尚未真正执行失败；同一入口只手动重投 RabbitMQ 消息，不增加 `retry_count`。

放弃方案：

- 暂不依赖 MQ 自动重试所有失败任务。原因是容易重复处理不可恢复错误，也会增加调试复杂度。

## D15：删除文档采用状态驱动的幂等删除流程

决策：删除文档时由 Java 先将 MySQL `document` 设置为 `DELETING` 且 `is_deleted=1`，使文档立即对业务不可见；然后同步调用 Python 删除 Qdrant vectors，再删除 MinIO object，最后将文档状态置为 `DELETED`。Python 删除时必须按 `ownerUserId + kbId + docId` filter 执行。Java 检索返回前再按 MySQL `document.is_deleted=0` 且 `index_status=INDEXED` 过滤 Qdrant 命中。

原因：

- Java 仍然是业务状态主边界，删除文档必须先让 MySQL 中的文档对用户不可见。
- Python 不访问 MySQL，也不维护业务删除状态，只负责执行向量库物理清理。
- 当前不支持同一文档同时索引和删除，也不允许两个删除调用并发执行。文档处于 `PENDING_INDEX`、`INDEXING`、`DELETING` 时，删除接口返回 `409004 DOCUMENT_BUSY`。
- Qdrant、MinIO 和 MySQL 当前没有分布式事务；删除失败时进入 `DELETE_FAILED` 并保持 `is_deleted=1`，用户再次调用删除接口即可幂等重试。
- 每次进入 `DELETING` 都递增 `delete_generation`；成功、失败和超时只能更新本 generation，防止迟到调用覆盖新一轮删除。

放弃方案：

- 暂不在 Java 中直接连接 Qdrant 删除 vectors。原因是会破坏“向量库 SDK 留在 Python AI 服务内”的边界。
- 暂不引入 outbox、补偿任务表、通用 Saga 或一致性协调器。原因是当前阶段选择 MySQL 状态、任务表和幂等重试的简单最终一致性方案。

## D16：一致性方案收敛为 MySQL 状态、任务表和幂等重试

决策：上传、索引和删除流程不引入通用 Saga、补偿事务框架、通用状态机、分布式锁、两阶段提交或 outbox。MySQL 是唯一业务事实来源，MinIO 和 Qdrant 不参与分布式事务，允许短暂不一致。

当前约束：

- 上传接口只保证文件已保存、`document` 已创建、`indexing_task` 已创建；索引异步执行。
- RabbitMQ 消息只保存 `documentId` 和 `indexingTaskId`，消费者重新查 MySQL。
- RabbitMQ 发布失败时不把任务打失败，任务保持 `PENDING`，由带 `last_publish_attempt_at` claim 的 PENDING 重投器再次投递，也支持通过 `/index-retry` 手动重投同一 attempt。
- 索引成功状态是 `document.index_status=INDEXED` 和 `indexing_task.status=SUCCESS`。
- 索引失败状态是 `document.index_status=INDEX_FAILED` 和当前 `indexing_task.status=FAILED`；人工重试新建 task attempt，并用 `document.current_indexing_task_id` 切换当前执行窗口。
- 删除失败状态是 `DELETE_FAILED`，文档保持 `is_deleted=1`，再次 DELETE 可幂等重试。
- 长时间 RUNNING 的当前索引 task 与 INDEXING document 在同一短事务内改为失败态；DELETING 超时按 `delete_generation` 条件更新，不自动补偿外部系统。
- Qdrant point id 由 `documentId + chunkIndex` 确定性生成；重复 upsert 覆盖同一 point。

原因：

- 当前阶段目标是展示真实后端工程闭环，而不是堆叠复杂一致性模式。
- MySQL 已经保存业务状态和任务状态，用条件更新可以处理主要并发边界。
- MinIO 和 Qdrant 是外部系统，纳入分布式事务会显著增加复杂度。
- 幂等删除和幂等 upsert 能覆盖 V1 的主要重试场景。

放弃方案：

- 放弃通用 Saga、补偿处理器、通用状态机、通用一致性协调器、复杂分布式锁、事件溯源和两阶段提交。
- 放弃为每个异常点单独设计补偿处理器。

## D17：文档状态只保留当前流程实际使用的状态

决策：`document.index_status` 当前只保留 `PENDING_INDEX`、`INDEXING`、`INDEXED`、`INDEX_FAILED`、`DELETING`、`DELETED`、`DELETE_FAILED`。不保留当前上传流程不会写入的预留上传状态。

原因：

- 当前上传接口是同步完成文件保存、`document` 创建和 `indexing_task` 创建后返回，成功后直接进入 `PENDING_INDEX`。
- 保留未写入状态会增加阅读成本，让面试官误以为存在 DB-first 上传或上传恢复流程。
- 项目目标是展示清晰的 Java 后端 + AI 工程闭环，状态数量应服务于真实流程，不为未来假设提前膨胀。

放弃方案：

- 放弃保留未使用的上传中状态。原因是当前没有对应接口流程、恢复机制或用户可见价值。

## D18：RAG 问答采用 Java 编排 + Python 生成

决策：Java 对外提供 `POST /api/v1/knowledge-bases/{kbId}/rag/ask`，先复用现有检索服务完成 owner 校验、Qdrant 检索和 MySQL 二次过滤；Python 只提供内部 `POST /api/v1/rag/generate`，根据 Java 传入的 chunk context 调 OpenAI-compatible LLM 生成答案。Python 将模型文本收敛成结构化 answer status 和实际引用编号，Java 再把编号映射回已过滤 chunk。

原因：

- Java 继续作为业务权限主边界，RAG 问答不会绕过已有知识库 owner 校验。
- retrieval/search 已经验证过 Qdrant filter 和删除残留过滤，RAG 复用它可以减少重复权限逻辑。
- Python 保留 AI provider 调用细节，Java 不直接绑定具体 LLM SDK 或 API 协议。
- 当前先做同步接口，便于稳定演示和排查；SSE、多轮会话、引用落库后续再单独推进。
- `citations` 必须来自答案实际出现且通过范围校验的 `[片段 n]`；候选 `retrievedChunks` 不能全部伪装成已引用证据。
- 无候选由 Java 返回 `NO_CONTEXT`；模型完整返回 sentinel 时返回 `INSUFFICIENT_CONTEXT`；正常回答返回 `ANSWERED`。无引用、越界引用和 sentinel 混用属于 provider 契约失败。

放弃方案：

- 暂不让客户端直接调用 Python RAG 接口。原因是 Python 不做登录态和完整业务权限判断。
- 暂不实现 SSE、Agent、query rewrite、rerank 或复杂 no-answer 模型。当前结构化拒答只是最小工程协议，不宣称解决开放问题的语义可靠性。

## D19：文档 checksum 去重只约束未删除文档

决策：`document` 表使用生成列 `active_checksum_sha256 = CASE WHEN is_deleted = 0 THEN checksum_sha256 ELSE NULL END`，并在 `(kb_id, active_checksum_sha256)` 上建立唯一索引。

原因：

- 上传接口的业务语义是“同一知识库内不能同时存在两份未删除的同内容文档”，而不是禁止历史 deleted 记录中出现重复 checksum。
- 上传流程先插入 `document` 抢占 active checksum，再写 MinIO object。这样同内容并发上传的失败请求不会先生成一个 UUID object，再依赖失败清理。
- 删除文档会把 `is_deleted` 置为 1；如果唯一索引使用 `(kb_id, checksum_sha256, is_deleted)`，同一文件“删除 -> 重传 -> 再删除”会在第二次删除时与第一条 deleted 历史行冲突。
- MySQL unique index 允许多条 `NULL`，因此 deleted 行的 `active_checksum_sha256=NULL` 可以保留原始 `checksum_sha256`，同时不参与唯一冲突。

放弃方案：

- 放弃用 checksum 作为稳定 MinIO objectKey 来复用对象。原因是删除失败后的旧 document 仍可能重试删除，如果新上传复用了同一个 objectKey，旧删除重试可能误删新 active 文档对象。
- 放弃删除时清空或改写 `checksum_sha256`。原因是 checksum 是文档真实元数据，历史记录保留真实值更利于排查。
- 放弃物理删除旧 document 行来释放唯一约束。原因是当前删除流程采用逻辑删除和状态驱动，保留历史状态更符合 V1 可解释性。

## D20：认证用户使用 Redis 短 TTL 快照缓存

决策：JWT 过滤器不再每次认证请求都直接查询 `user` 表，而是先读取 Redis 中的认证用户快照；缓存未命中、Redis 不可用或缓存反序列化失败时回源 MySQL。登录成功后会预热认证缓存。

当前约束：

- 缓存 key：`ekb:auth:user:{userId}`。
- 默认 TTL：300 秒，额外增加 0～60 秒随机抖动。
- 缓存内容只包含 `id`、`username`、`nickname`、`email`、`status`、`isDeleted`。
- 缓存不保存 `passwordHash`。
- Redis 只是认证链路的加速层；MySQL 仍是用户状态最终来源。
- Redis 故障时降级为 MySQL 查询，不让缓存成为认证单点故障。

原因：

- JWT 过滤器位于所有受保护 API 前面，当前用户读取是最典型的高频读、低频改场景。
- 当前项目没有账号状态频繁变更场景，短 TTL 带来的状态延迟可以接受。
- 登录成功时已有完整用户记录，预热缓存可以减少登录后的首个业务请求再次查询用户表。
- 只缓存认证快照，不缓存完整用户对象，可以避免密码哈希等敏感字段进入 Redis。

放弃方案：

- 暂不缓存所有用户查询。原因是当前没有用户列表、用户搜索等高频场景。
- 暂不把 access token 全量写入 Redis 或做 access token 黑名单。原因是当前普通退出只要求撤销 refresh token，access token 等待较短 TTL 自然过期。
- 暂不使用布隆过滤器或空值缓存。原因是 JWT 中的 userId 已经由签名保护，当前不存在大量随机用户 ID 穿透场景。
- 暂不追求账号禁用后的立即失效。若后续新增账号禁用或删除接口，应在写 MySQL 后删除对应认证缓存 key。

## D21：退出登录采用 Redis Refresh Token 会话撤销

决策：认证体系从纯 access token 调整为短 TTL JWT access token + Redis 有状态 refresh token。普通退出登录只撤销当前 refresh token，access token 不进入 Redis 黑名单，等待自然过期。

当前约束：

- Access Token 是 JWT，默认有效期 1800 秒。
- Refresh Token 是高熵随机字符串，默认有效期 1209600 秒。
- Redis refresh token key 使用 `sha256(refreshToken)`，不保存明文 refresh token。
- 登录成功签发 access token 和 refresh token。
- `POST /api/v1/auth/refresh` 会消费旧 refresh token 并签发新的一组 token。
- `POST /api/v1/auth/logout` 删除当前 refresh token 会话。
- 前端遇到业务请求 401 时，会用 refresh token 静默刷新一次。

原因：

- 普通退出的核心诉求是“不能继续续期”，而不是把每个 access token 都变成 Redis session。
- Access Token TTL 缩短后，退出后旧 access token 的风险窗口可控。
- Refresh Token 放 Redis，能体现真实 token 状态管理，又不破坏 JWT access token 的轻量性。
- Refresh Token 轮换可以降低 refresh token 被重复使用的风险。

放弃方案：

- 暂不把所有 access token 存入 Redis。原因是会把 JWT 退化成服务端 session，读写压力和实现复杂度都上升。
- 暂不做普通退出时的 access token 黑名单。原因是当前 access token TTL 已缩短，普通退出不需要立即撤销 access token。
- 暂不实现退出所有设备。原因是当前没有设备管理页面或账号安全中心，先保留 `ekb:auth:user-refresh:{userId}` 集合为后续扩展基础。
- 暂不实现 `tokenVersion`。原因是当前没有修改密码、封禁用户或账号异常处理接口；后续增加这些高风险操作时，再通过 `tokenVersion + 删除全部 refresh token + 删除认证缓存` 实现强制失效。

## D22：知识库 owner 权限校验使用 Redis 短 TTL 访问缓存

决策：知识库详情、文档入口、检索入口和 RAG 入口不再各自直接查询 `knowledge_base` 表完成 owner 校验，而是统一通过 `KnowledgeBaseAccessService`。该服务优先读取 Redis 中的知识库访问快照，缓存未命中、不可解析或 Redis 不可用时回源 MySQL。

当前约束：

- 缓存 key：`ekb:kb:meta:{kbId}`。
- 默认 TTL：300 秒，额外增加 0～60 秒随机抖动。
- 缓存内容包含 `id`、`ownerUserId`、`name`、`description`、`visibility`、`status`、`createdAt`、`updatedAt`、`isDeleted`。
- 缓存只按 `kbId` 保存知识库元数据，不缓存“某个 userId 对某个 kbId 无权访问”的负结果。
- 缓存命中后仍由 Java 比较 `ownerUserId` 和当前用户 id；不匹配时返回 `KNOWLEDGE_BASE_NOT_FOUND`。
- 首次读取知识库访问快照时写入缓存，删除知识库事务提交后删除缓存。
- Redis 故障时回源 MySQL，不让缓存成为权限校验单点。

原因：

- 知识库 owner 校验位于文档上传、文档列表、知识库详情、检索和 RAG 主链路上，是高频、低变更、可接受短暂不一致的读场景。
- 缓存 owner 元数据能减少重复的 `knowledge_base` 表查询，同时不削弱 Java 作为权限主边界的职责。
- 只缓存知识库元数据，不缓存列表或检索结果，可以保持失效边界清晰。
- 删除时用事务提交后的缓存失效，避免 Redis 状态跑在 MySQL 事务前面。

放弃方案：

- 暂不缓存知识库列表。原因是分页、排序、创建和删除都会影响列表，当前没有足够高频证据。
- 暂不缓存文档列表。原因是索引状态、删除状态和分页共同影响结果，失效复杂。
- 暂不缓存检索结果或 RAG 答案。原因是权限、文档版本、Qdrant 残留和 LLM 输出都会影响正确性。
- 暂不使用布隆过滤器或缓存无权访问结果。原因是当前没有大量随机 kbId 穿透证据，缓存负权限还容易引入 stale deny。

## D23：区分 PENDING 传输恢复与 FAILED 业务重试，并使用 execution fencing

决策：索引 task 采用 append-only attempt。初次上传创建 `attempt_no=0, trigger_type=UPLOAD`；只有 PENDING 传输恢复可以重投同一 task，FAILED 业务重试必须创建 `MANUAL_RETRY` task。`document.current_indexing_task_id` 是索引状态写入的 fencing token，`document.delete_generation` 是删除终态的 fencing token。

当前约束：

- `(document_id, attempt_no)` 唯一约束阻止并发创建同一后继 attempt。
- `retry_count/max_retry` 保持 API 兼容并真正执行三次人工重试上限。
- RabbitMQ 消息仍只保存 `documentId + indexingTaskId`；消费者重新查库并校验 task/document 的 document、kb、owner 关联。
- 非 current 的合法历史消息按 at-least-once 语义 ack 跳过；静态关联错误进入 DLQ。
- task/document 的 RUNNING、SUCCESS、FAILED 和 timeout 更新采用同一锁顺序和短事务；document CAS 必须匹配 current task。
- PENDING 扫描器先原子更新 `last_publish_attempt_at` 再发布，即使发布失败也等待下一节流窗口。
- 删除不持有跨 Qdrant/MinIO 的长事务；每次删除只认领一个 generation，迟到结果只能 no-op。
- 文档上传与知识库删除锁定同一 `knowledge_base` 行，避免删除检查与新文档插入交错。
- MinIO put 成功后注册 transaction synchronization；只有明确 ROLLED_BACK 才按 objectKey 幂等清理。commit outcome 为 UNKNOWN 时仅告警并保留 object，避免误删已经提交业务行对应的文件。

原因：

- 复用同一 taskId 会让超时前的旧 worker 与重试后的新 worker 共享 RUNNING 标识，旧结果可能误写新的执行窗口。
- 将“消息没发出去”和“AI 执行失败”区分开，既保留 at-least-once 恢复能力，也保留失败历史和可解释的 attempt 证据。
- generation/token 是针对当前真实竞态的局部设计，比引入通用状态机、分布式锁、Saga 或 outbox 更符合项目规模。

边界：

- MySQL fencing 不能撤回已经发出的 Python HTTP 调用，也不宣称阻止旧 worker 触碰 Qdrant。稳定 point id 只提供幂等覆盖；旧调用若最后执行，仍可能覆盖或删除 current vectors。
- 当前不实现自动指数退避业务重试、通用补偿平台或 task-aware Qdrant point 版本。

## D24：可观测性采用 requestId 关联、阶段耗时和低基数指标，且保持旁路语义

决策：同步 HTTP 请求使用安全归一化后的 `X-Request-Id` 关联 Java、FastAPI 和外部 LLM；异步索引 attempt 使用稳定的 `index-task-{indexingTaskId}`。Python 返回阶段耗时和 provider usage，Java 记录结构化日志、低基数 Micrometer 指标，并 best-effort 写入已有 `model_call_log`。

当前约束：

- Java 和 Python 都只接受最多 64 字符、匹配 `[A-Za-z0-9._:-]+` 的 requestId；非法或缺失值生成 UUID，避免日志注入。
- 上传 HTTP 请求与后续异步 worker 不共享同一个短生命周期 requestId。RabbitMQ 消息体继续只保存两个业务 ID；correlationId 和消费 MDC 都由已持久化 taskId 确定性生成。
- Python 索引拆分 download/parse/split/embedding/vector store/total，检索拆分 embedding/vector store/total，RAG 返回非流式 LLM 完整耗时和可选 token usage。
- Python 异常路径记录有限的失败阶段和异常类型，但不记录异常正文、对象 key、query/question、prompt/context/answer 或凭证。
- `model_call_log` 只记录有明确语义的模型调用：成功的索引/检索 embedding 与已经发起的 CHAT 成功/失败。无索引文档/无上下文短路不写伪造模型调用；下载、解析或 Qdrant 失败也不武断归类为 embedding 失败。
- `model_call_log` 写入失败和指标记录失败不得反向影响已经提交的业务结果。错误字段做敏感内容识别、凭证脱敏和长度限制，不保存 prompt/question/chunk/answer 正文。
- 指标标签只允许固定的 application 和 operation、outcome、failure_stage、phase、type 等有限集合，禁止把 requestId、userId、kbId、documentId、taskId 或错误全文做 tag。
- Actuator 只暴露 `/actuator/prometheus`，并继续落入 Spring Security `.anyRequest().authenticated()`，不恢复 health 端点，也不为抓取端点新增匿名权限。

原因：

- 当前真正需要的是回答“一次请求慢/失败在哪一段”，requestId、阶段耗时、终态指标和模型调用记录已经足够形成可验证证据。
- 用 taskId 关联异步执行比复制上传 requestId 更准确：一个 task 可被 PENDING 重投，而 FAILED 业务重试会创建新的 attempt。
- 高基数业务标识留在日志和 MySQL 中，枚举维度留在时序指标中，可以避免 Prometheus series 数量随用户和文档增长。
- best-effort 旁路避免观测系统故障改变索引状态机或用户问答结果。

边界与放弃方案：

- 当前不建设 Grafana dashboard、告警、长期指标存储或生产级 tracing 平台。
- 当前同步生成只能记录完整 LLM latency，不记录 TTFT。
- 当前不把调用日志字段扩展成正式 RAG 评测/实验平台；现有固定集仍只作为轻量回归基线。
- FastAPI 内部接口尚未实现服务 token/mTLS，仍依赖部署网络边界；requestId 不是身份认证。该问题应作为独立安全边界处理，不能混入 P0-2 伪装完成。

## D25：可靠性证据分层，外部故障采用交互式演练而非生产测试后门

决策：P0-3 将可靠性证据明确分成“自动测试已通过、演练脚本已就绪、外部演练已通过、设计落地”四类。current-task/delete-generation fencing 用确定性 Java 测试回归；RabbitMQ、Qdrant、MinIO 的真实不可用与恢复使用交互式脚本，由操作者控制本地依赖，不在生产 API 或配置中加入故障注入开关。

当前约束：

- Java 测试通过 mapper CAS 返回序列和 `rollbackOnly` 验证迟到 worker、timeout、删除 generation 与失败状态；测试结论只覆盖控制流和条件更新形状，不冒充真实 MySQL 并发事务。
- `scripts/test_rabbitmq_reliability.sh` 使用 RabbitMQ management API 发布重复/非法消息和读取 DLQ 计数；broker 的停止与恢复由操作者完成。
- `scripts/test_document_delete_failure_recovery.sh qdrant|minio` 通过公开业务 API和固定只读 SQL验证 `DELETE_FAILED -> DELETED`、`is_deleted=1` 与 generation；不新增可读取已删除内部状态的管理 API。
- 故障脚本只从环境变量或 MySQL client defaults file 读取本地凭证，不打印凭证；脚本不会执行数据库修复 SQL。
- 检索/RAG 权限 fixture 保存在 `scripts/fixtures`，不依赖被忽略的 `/tmp` 运行时目录，保证新 clone 可执行。
- 迟到 Python worker 的外部 Qdrant 副作用不是当前保证。P0-3 只验证旧 worker 不能覆盖 current MySQL 状态，并继续公开该残余风险。

原因：

- 求职证据的价值来自可复现和边界准确，而不是脚本、类或状态名的数量。
- 为了制造慢 worker 或依赖错误而增加生产故障端点，会扩大攻击面并污染 Java/Python 业务边界。
- 即使仓库已有统一 Compose 服务名，故障脚本仍不主动 stop/start 容器：操作者控制故障注入，脚本只断言业务状态，避免误停共享或非 Compose 环境中的依赖。
- RabbitMQ/Qdrant/MinIO 外部演练成本较高，不应放入默认单元测试；但必须留下明确命令、前置条件和证据字段。

放弃方案：

- 不增加 chaos 平台、Toxiproxy、通用故障注入框架或 Testcontainers 集群。
- 不增加 test-only Controller、管理 API、慢响应开关或绕过权限的内部查询接口。
- 不让脚本猜测并停止固定容器名，也不自动修改业务表伪造状态。
- 不把 shell 语法通过、Mockito 测试通过或历史 Redis Stream 记录写成 RabbitMQ/Qdrant/MinIO 外部故障已经通过。

## D26：核心证据采用自包含 fixture、跨语言契约与单一验证入口

决策：仓库用 `scripts/verify.sh` 统一运行 shell 语法、Java 单元测试和 Python 核心测试；GitHub Actions 调用同一入口。Java/Python RAG DTO 使用 `contracts/ai` 中的共享 JSON fixture 双向校验；固定 RAG 文档和问题必须随仓库提交，运行结果保持忽略。

原因：

- 新 clone 不应依赖个人绝对路径、被忽略的临时文件、已启动中间件或真实 LLM 才能验证核心规则。
- 单独测试 Java record 和 Pydantic model 仍可能让字段名漂移；共享 fixture 能在 PR 阶段暴露内部 HTTP 契约不兼容。
- loader、splitter、Qdrant filter/payload 和生成协议是 AI 工程链路的高价值确定性边界，适合轻量 CI。
- E2E、真实故障和模型质量受环境影响，必须与核心 CI 分层，避免 flaky 结果和伪证据。

边界与放弃方案：

- CI 不下载 embedding 模型，不启动 MySQL/RabbitMQ/MinIO/Qdrant，不调用真实 LLM。
- 不追求覆盖率数字，不把所有 shell E2E 塞入 PR gate，不为 CI 引入大型 Testcontainers 拓扑。
- `eval/rag/results` 不提交；历史结果只能明确标记为当时协议的证据。

## D27：最小 no-answer 使用结构化协议，统一相似度阈值默认关闭

决策：Python prompt 要求无法回答时只输出 `__EKB_NO_ANSWER__`，并将响应解析为 `answer_status`、`cited_context_indexes`、`no_answer_reason`。Java 返回 `answerStatus`、`noAnswer`、`noAnswerReason`，只映射实际引用。可选 `RAG_MINIMUM_RELEVANCE_SCORE` 默认 `-1` 关闭。

原因：

- 中文关键词判断“无法回答”容易误判正常答案，也无法稳定区分业务短路与模型拒答。
- 受控 sentinel 和引用范围校验能形成明确 API 契约、失败行为和单元测试证据。
- 历史 fixed baseline 中可回答题 top1 最低分约 0.4685，而无答案题最高分约 0.5627，二者重叠；启用一个未经验证的全局阈值会误拒可回答问题。
- 实际 citation 与 candidate retrieval 分离后，引用正确性才具有可信语义。

边界与放弃方案：

- 该协议不等于 entailment/groundedness 检测，也不保证模型永远遵循 sentinel。
- provider 返回无引用答案、越界编号或混合 sentinel 时返回 503，不猜测或伪造引用。
- 暂不增加 hybrid、rerank、query rewrite、分类模型或正式 RAG 评测平台；阈值启用前必须用当前代码和数据重跑。

## D28：Compose 只服务本机可复现演示，不包装成生产编排

决策：根 `compose.yaml` 编排六个基础/初始化服务和两个应用服务；使用 `.env` 注入本地凭证、命名卷保存数据与模型、`model-init` 一次性下载 embedding，所有宿主端口绑定 `127.0.0.1`。

原因：

- 求职演示需要降低新环境准备成本，并明确 Java/Python/中间件的真实拓扑。
- embedding 模型体积大且不能进 Git，把它放命名卷可复用下载结果并对运行服务只读。
- FastAPI 尚无独立服务 token，本机端口绑定比把内部 API 暴露到局域网更符合当前信任边界。
- 单机 Compose 足以解决当前复现问题，不需要 Kubernetes、Helm 或服务注册中心。

边界与放弃方案：

- V1 只允许空 MySQL 卷首次初始化；已有库按缺失迁移处理。
- 静态 `docker compose config` 通过不等于镜像 build、服务 up 或 E2E 已验证，证据必须分开记录。
- 不新增匿名 health API、生产 TLS、高可用、备份、滚动升级或 secret manager 的虚假包装。
- 不把 Compose 文件称为生产部署方案，不扩展 Kubernetes/Helm/服务网格。
