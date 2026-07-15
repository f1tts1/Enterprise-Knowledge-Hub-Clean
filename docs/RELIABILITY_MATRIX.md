# 可靠性验证矩阵

本文档记录 Enterprise Knowledge Hub 当前异步索引、删除一致性和权限隔离相关的可靠性验证计划。它的目标是为求职展示提供可解释证据，而不是引入复杂的生产级一致性框架。

## 当前可靠性边界

当前系统选择：

- MySQL 是业务事实来源。
- RabbitMQ 只作为索引任务触发队列。
- MinIO 和 Qdrant 不参与分布式事务。
- Python 不修改 MySQL，只通过 HTTP DTO 向 Java 汇报 AI/向量库处理结果。
- 失败状态先落库可见，再由人工重试或再次调用删除接口恢复。
- 重复消息和迟到结果通过不可变索引 attempt、`current_indexing_task_id`、`delete_generation`、条件更新、幂等 point id/filter 和 MySQL 二次过滤处理。

边界说明：attempt/generation fencing 保护 MySQL 当前业务状态，不等于旧 Python worker 完全没有 Qdrant 外部副作用。不同 attempt 仍写同一组稳定 point id；若旧调用最后执行，仍可能覆盖或删除 current attempt 的 vectors，当前没有跨存储 execution fencing。

当前不引入：

- 通用 Saga 框架。
- outbox 补偿任务表。
- 分布式锁。
- 两阶段提交。
- Kafka 替换 RabbitMQ。
- 复杂死信队列平台。

## 状态机摘要

### document.index_status

| 状态 | 含义 | 用户可见性 |
|---|---|---|
| `PENDING_INDEX` | 上传完成，等待异步索引 | 可在文档列表看到 |
| `INDEXING` | Java worker 正在调用 Python | 可在文档列表看到 |
| `INDEXED` | Qdrant 写入完成，Java 已确认数量 | 可检索、可 RAG |
| `INDEX_FAILED` | 索引失败或超时 | 可见，可人工重试 |
| `DELETING` | 删除已开始，业务不可见 | 不可见 |
| `DELETED` | Qdrant vectors 和 MinIO object 清理完成 | 不可见 |
| `DELETE_FAILED` | 外部清理失败，可再次 DELETE | 不可见 |

### indexing_task.status

| 状态 | 含义 |
|---|---|
| `PENDING` | 等待 RabbitMQ 消费或重投 |
| `RUNNING` | Java worker 已接管并调用 Python |
| `SUCCESS` | Python 完成 Qdrant 写入，Java 校验成功 |
| `FAILED` | Python 调用失败、响应不符合契约、文档删除或超时 |

### 索引 attempt 规则

| 场景 | task 语义 | document 指针 |
|---|---|---|
| 初次上传 | 新建 `attempt_no=0, trigger_type=UPLOAD` | 指向 attempt 0 |
| PENDING 传输恢复 | 重投同一 task，不增加 attempt/retry | 保持不变 |
| FAILED 业务重试 | 新建 `MANUAL_RETRY` task，旧 FAILED task 不再修改 | CAS 切换到新 task |
| 旧消息/旧 worker 迟到 | 非 current attempt 只能跳过或让终态 CAS no-op | 不允许覆盖 current 状态 |

`max_retry=3` 是真实上限；初始 attempt 不计入三次人工失败重试。

## 验证矩阵

| 编号 | 场景 | 验证目标 | 触发方式 | 预期结果 | 覆盖方式 | 状态 |
|---|---|---|---|---|---|---|
| R01 | RabbitMQ 重复消息 | 同一 task 终态后重复投递不会重新索引；task/document 静态关联必须匹配 | 文档索引成功后向 RabbitMQ 手动发布同一 JSON 消息 | `document=INDEXED`、`task=SUCCESS` 保持不变；非法关联进入 DLQ | Java 单测；RabbitMQ 手工步骤待执行 | 部分验证 |
| R02 | RabbitMQ 发布失败后 PENDING 重投 | 上传事务已提交但队列短暂失败时保持 PENDING，并避免每轮热循环堆重复消息 | 停 RabbitMQ 后上传，等待多个调度周期，再恢复 RabbitMQ | 同一 attempt 的 `retry_count` 不增加；`last_publish_attempt_at` claim 在 delay 内只允许一次发布尝试 | Java claim 单测；RabbitMQ 故障演练待执行 | 部分验证 |
| R03 | FastAPI 不可用 | Python 不可用时，失败状态落库可见 | 停 FastAPI 后上传文档 | `document=INDEX_FAILED`、`task=FAILED` | `scripts/test_index_retry.sh` 前半段 | 已有脚本 |
| R04 | 人工重试索引 | 失败后新增 attempt，历史失败证据不被复活 | 恢复 FastAPI 后调用 `/index-retry` | attempt 0 保持 FAILED；attempt 1 为 `MANUAL_RETRY/PENDING -> RUNNING -> SUCCESS`；document current pointer 切换 | 已更新 `scripts/test_index_retry.sh` + Java 单测 | 脚本待执行 |
| R05 | 旧 worker 迟到返回 | timeout/retry 后旧 attempt 不能覆盖 current MySQL 状态 | 人工构造慢 Python 或调短超时后让旧调用迟到 | 旧 task/document 终态 CAS no-op；不承诺旧调用完全不触碰 Qdrant | current task fencing 代码与单测；外部故障演练待补 | 部分验证 |
| R06 | 删除与索引/删除冲突 | `PENDING_INDEX` / `INDEXING` 不能删除，`DELETING` 不能启动第二次删除 | 上传后立即 DELETE，或对 DELETING 文档再次 DELETE | 返回 `409004 DOCUMENT_BUSY` | Java 单测 + 手工步骤 | 部分验证 |
| R07 | 删除 Qdrant vectors 失败 | 删除开始后业务不可见，且只由本 generation 写失败态 | 删除时让 FastAPI/Qdrant 不可用 | `is_deleted=1`、`index_status=DELETE_FAILED`；再次 DELETE 认领新 generation | Java 单测；外部故障演练待补 | 部分验证 |
| R08 | 删除 MinIO object 失败 | MinIO 删除失败不让文档重新可见，旧 generation 不覆盖新重试 | 删除时让 MinIO 不可用或认证失败 | 本 generation 进入 `DELETE_FAILED`；迟到终态对新 generation no-op | Java 单测；外部故障演练待补 | 部分验证 |
| R09 | 删除后同内容重传 | deleted 历史 checksum 不阻塞同内容再次上传和再次删除 | 同内容文档上传、删除、重传、再删除 | 第二次上传和删除均成功 | `scripts/test_document_reupload_delete.sh` | 已有脚本 |
| R10 | 删除后 Qdrant 残留防线 | 即使 Qdrant 残留 vectors，Java 返回前也按 MySQL 二次过滤 | 删除文档后检索同一关键词 | 不返回已删除 docId/chunk | `scripts/test_retrieval_permission.sh` | 已有脚本 |
| R11 | 跨用户检索隔离 | Alice/Bob 不能检索或 RAG 到对方知识库内容 | Alice/Bob 各自上传私有文档并互查 | 直接访问对方 kb 返回 404；本库检索不出现 forbidden terms | `scripts/test_retrieval_permission.sh`、`scripts/test_rag_permission.sh` | 已有脚本 |
| R12 | 空知识库 RAG 短路 | 无已索引 chunk 时不调用 LLM | 空知识库调用 RAG | 返回固定无上下文答案，citations/retrievedChunks 为空 | `scripts/test_rag_empty_kb.sh` | 已有脚本 |
| R13 | 并发上传触发 Qdrant schema 初始化 | 两个索引任务接近同时写 Qdrant 时，不应因 collection 或 payload index 已存在而失败 | 并发上传两份文档；首次 schema 初始化压力验证需在一次性环境先清空 collection | 两个文档均 `INDEXED/SUCCESS`，两个唯一标记均可检索 | 手工步骤，当前无脚本 | 待验证 |
| R14 | 同知识库并发同内容上传 | 两个请求同时上传相同文件时，只能创建一个 active document，失败请求不应先写出额外 MinIO object | 同一用户同一知识库并发上传同一文件 | 一条成功，一条 `409003 DOCUMENT_ALREADY_EXISTS`；成功文档可索引 | 历史记录；当前无脚本 | 待重建证据 |
| R15 | 索引 timeout 原子性 | current task/document 必须同时进入失败态 | 构造 `RUNNING + INDEXING` 且 started_at 超时 | 同一短事务写 `FAILED + INDEX_FAILED`；任一 CAS 失败则整体回滚 | Java 单测；真实 MySQL 事务验证待补 | 部分验证 |
| R16 | 并发人工 retry | 同一 FAILED attempt 只能创建一个后继 attempt | 两个请求并发调用 `/index-retry` | 唯一约束 + current pointer CAS 只允许一个赢家，无孤立新 task | Java 单测；真实 MySQL 并发验证待补 | 部分验证 |
| R17 | 知识库删除与文档上传 | “无活动文档检查”不能被并发上传穿透 | 同一 KB 并发 DELETE 与上传 | 两条写路径按 knowledge_base 行锁串行；要么删除冲突，要么上传得到 NOT_FOUND | 真实 MySQL 并发验证待补 | 设计落地 |
| R18 | MinIO 写入后的事务回滚 | 数据库明确回滚时不留下本次孤儿 object，同时不误判 commit outcome unknown | 在 MinIO put 后注入事务 rollback / unknown | ROLLED_BACK 按 objectKey 清理；UNKNOWN 只告警并保留 object | 代码路径；事务故障注入待补 | 设计落地 |

## 手动验证顺序

推荐按从稳定到破坏性排序执行：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"

# 1. 权限、删除残留和基本检索隔离
./scripts/test_retrieval_permission.sh

# 2. 同内容文档删除后重传
./scripts/test_document_reupload_delete.sh

# 3. 知识库删除保护
./scripts/test_knowledge_base_delete_guard.sh

# 4. RAG 权限和空知识库短路
./scripts/test_rag_empty_kb.sh
./scripts/test_rag_permission.sh

# 5. FastAPI 不可用后的失败落库与人工重试
./scripts/test_index_retry.sh
```

R01、R02、R06、R13、R14 当前没有对应 shell 脚本；按矩阵中的触发方式手工执行，或后续补真实存在且可重复运行的脚本。不要把历史文件名当作当前可执行证据。

## 已验证记录

### 2026-06-29

以下是 Redis Stream 队列时期的历史记录。表中脚本已不在当前仓库，不能证明 2026-07 的 RabbitMQ 实现：

| 脚本 | 覆盖场景 | 结论 |
|---|---|---|
| `scripts/test_concurrent_duplicate_upload.sh` | 同一知识库内并发上传同内容文件 | 已通过：一条上传成功，一条返回 `409003 DOCUMENT_ALREADY_EXISTS`；成功文档可继续索引。 |
| `scripts/test_pending_index_republish.sh` | Redis XADD 失败后 `PENDING_INDEX/PENDING` 手动重投 | 历史已通过：恢复 Redis 后调用 `/index-retry` 可重投同一任务并最终索引成功，`retry_count` 不增加。 |
| `scripts/test_indexing_duplicate_message.sh` | Redis Stream 中存在重复 `documentId + indexingTaskId` 消息 | 历史已通过：重复消息不会把终态任务重新拉回 RUNNING，不会重复执行同一个索引任务。 |

### 2026-07-15

本轮执行 `mvn -q -o test`，20 个 P0-1 Java 单元测试全部通过，覆盖 immutable attempt、maxRetry、PENDING 同 attempt 重投、消息静态关联与 current task、Python 回显 ID、发布 claim、timeout rollback 标记、删除 generation wrapper、事务完成状态和知识库清理守卫。这里明确不把 Mockito 测试写成 MySQL 并发或 RabbitMQ/Qdrant 外部故障证据。

另在 `/tmp` 隔离、`--skip-networking` 的 MySQL 9.5 临时实例执行了两条数据库路径：当前 V1 全新初始化，以及提交前旧 V1 数据执行 V2→V3。结果确认 7 个新列、3 个新索引、attempt 回填、legacy timeout 半状态归一化、`updated_at` 保留和唯一约束；构造同 document 多 task 时，V3 preflight 在任何持久 DDL 前由临时表主键冲突终止。临时实例已关闭，未触碰本地业务库。

### 2026-07-09

本轮完成索引队列从 Redis Stream 到 RabbitMQ 的代码层替换：

| 场景 | 当前代码状态 | 仍需验证 |
|---|---|---|
| RabbitMQ 发布失败后 PENDING 重投 | `IndexingServiceImpl` 发布失败不改写 MySQL 失败态，`PendingIndexingTaskRepublishScheduler` 会扫描 PENDING 任务重新发布。 | 停 RabbitMQ 上传后恢复，并调用 `/index-retry`。 |
| RabbitMQ 重复消息幂等 | `IndexingServiceImpl.markRunning` 仍只允许 `task=PENDING` 进入 RUNNING，终态任务会被跳过。 | 手动向 RabbitMQ 发布同一 `documentId/indexingTaskId`。 |
| RabbitMQ DLQ | `IndexingQueueConsumer` 对非法消息和消费者自身异常执行 `basicReject(requeue=false)`。 | 向 queue 投递非法 JSON，确认进入 `ekb.indexing.tasks.dlq`。 |

本轮验证后，可以作为面试证据讲清楚：

- 并发同内容上传依赖 MySQL active checksum 唯一索引，而不是靠前置查询。
- 队列投递失败保留 MySQL `PENDING` 事实状态，并支持手动重投。
- RabbitMQ 至少一次投递语义由 MySQL 条件更新兜底，重复消息不会重复索引。

仍需注意：上述验证阻止新的并发上传留下重复 MinIO object，但不会自动清理历史上已经存在的 MinIO 孤儿对象。历史对象清理应先做只读盘点，再决定是否人工删除。

## 关键设计解释

### 为什么失败任务会 ack RabbitMQ 消息？

当前 `IndexingQueueConsumer` 调用 `indexingService.processIndexingTask(...)` 正常返回后会 ack 消息。Python 调用失败时，`IndexingServiceImpl` 会把错误写入 MySQL，并将任务标记为 `FAILED`，不会依赖 RabbitMQ 自动反复投递。

这是有意选择：当前阶段优先让失败状态对用户和调试者可见，再通过 `/index-retry` 进行人工重试。这样可以避免不可恢复错误被队列无限重试。

### 为什么 RabbitMQ 消息只存 ID？

RabbitMQ 消息只保存 `documentId` 和 `indexingTaskId`。消费者重新查 MySQL 获取文件位置、owner、kb、checksum 和状态。这样可以避免消息体与数据库记录不一致。

### 为什么 PENDING_INDEX 也允许调用 index-retry？

如果上传事务已经提交，但 RabbitMQ 发布在 afterCommit 阶段失败，MySQL 中会留下 `document.index_status=PENDING_INDEX` 和 `indexing_task.status=PENDING`。这个状态还没有真正开始索引，所以手动调用 `/index-retry` 只重新投递同一个任务，不增加 `retry_count`。

如果任务已经进入 `INDEXING`，仍然返回 `409004 DOCUMENT_BUSY`。如果任务已经 `INDEX_FAILED/FAILED`，`/index-retry` 才新建不可变 `MANUAL_RETRY` attempt，并令 `retry_count + 1`；旧 FAILED task 不再修改。

### 为什么需要 immutable attempt 和 current pointer？

旧实现复用同一 taskId。worker 超时后，如果人工重试把同一行再次改为 RUNNING，旧 worker 可以用相同 taskId 误写新执行窗口。现在每次失败重试都新增 task，`document.current_indexing_task_id` 作为 fencing token；worker 的 document 终态更新必须同时匹配 current task。

`max_retry` 同时从展示字段变为真实约束，避免接口对外宣称上限但代码无限重试。

### 为什么删除先写 MySQL 不可见？

删除开始时先把 `document.is_deleted=1`，并将状态置为 `DELETING`。这样即使后续 Qdrant 或 MinIO 删除失败，文档也不会重新出现在列表、详情、检索或 RAG 中。

### 为什么上传时先写 MySQL 再写 MinIO？

同一知识库内的 active 文档通过 `(kb_id, active_checksum_sha256)` 唯一索引去重。上传时先插入 `document`，让 MySQL 唯一索引抢占 checksum；抢占成功后再写 MinIO object。

这样两个同内容上传并发到达时，失败的一方会在 `documentMapper.insert` 阶段得到 `409003 DOCUMENT_ALREADY_EXISTS`，不会先生成一个 UUID object 再依赖失败清理。`document` 行仍在当前事务里，RabbitMQ 消息只会在事务提交后投递；如果 MinIO 写入失败，事务回滚，业务表不会留下指向缺失 object 的可见记录。

### 为什么不做分布式事务？

MySQL、MinIO、Qdrant 和 Redis 无法简单纳入同一个事务。当前项目规模下，用状态机、条件更新、幂等删除和人工重试，比引入复杂一致性框架更可解释，也更适合求职项目展示。

## 当前缺口

以下场景仍建议后续补专项脚本或测试：

- R05：旧 worker 迟到返回，需要可控慢 Python 或缩短超时配置。
- R07/R08：Qdrant 或 MinIO 删除失败，需要更可控的外部依赖故障注入。
- R15/R16/R17：事务回滚、并发 retry 和知识库行锁仍需真实 MySQL 并发测试；Mockito 单测不能替代数据库约束证据。
- R18：ROLLBACK / UNKNOWN 两类事务完成状态仍需可控故障注入；UNKNOWN 无法安全自动判定，不承诺零孤儿。
- Qdrant 残余：旧 Python 调用最后执行时可能覆盖或删除 current vectors；完整解决需要 task-aware point/version、current-attempt 过滤与旧版本清理，本阶段不扩展。

这些缺口不是当前主链路阻塞点。面试时应该说明：当前选择状态驱动和人工重试，而不是宣称已经具备生产级自动补偿系统。
