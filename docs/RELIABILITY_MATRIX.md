# 可靠性验证矩阵

本文档记录 Enterprise Knowledge Hub 当前异步索引、删除一致性和权限隔离相关的可靠性验证计划。它的目标是为求职展示提供可解释证据，而不是引入复杂的生产级一致性框架。

确定性检查统一从仓库根目录执行 `./scripts/verify.sh`；测试分层见 `docs/TESTING.md`。`compose.yaml` 只是本机演示拓扑，当前验证边界见 `docs/DEPLOYMENT.md`，Compose 配置可解析不等于镜像构建、服务启动或故障演练已经通过。

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

## 证据分级

本矩阵严格区分四类证据，避免把“代码存在”写成“真实故障已经验证”：

- `自动测试已通过`：仓库内确定性单元测试实际执行通过；只证明对应控制流、条件更新形状或旁路语义。
- `演练脚本已就绪`：脚本和 fixture 已纳入仓库并通过语法检查，但需要本地依赖运行和人工停启服务，未执行前不写“外部故障已验证”。
- `外部演练已通过`：在记录的日期、环境和命令下真实停止/恢复 RabbitMQ、Qdrant、MinIO 或 FastAPI，并保存关键状态结论。
- `设计落地`：生产代码存在对应约束，但尚缺自动测试或真实依赖证据。

Mockito/MyBatis wrapper 测试不能替代真实 MySQL 事务和并发竞争；MySQL fencing 也不能证明旧 Python 调用没有 Qdrant 外部副作用。

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
| R01 | RabbitMQ 重复消息 | 同一 task 终态后重复投递不会再次调用 Python 索引；非法 JSON 进入 DLQ | 成功索引后由 management API 重投同一消息，再投非法 JSON | task/document、`document_index` HTTP 尝试数和模型日志数不变；DLQ 消息数增加 | Java 单测；`scripts/test_rabbitmq_reliability.sh` | 自动测试已通过；演练脚本已就绪 |
| R02 | RabbitMQ 发布失败后 PENDING 重投 | 上传事务已提交但 broker 短暂失败时保持 PENDING，并由 scheduler 自动恢复 | 停 RabbitMQ 后上传并等待，再恢复 RabbitMQ；不调用 `/index-retry` | 同一 task/attempt/retry 保持不变，最终 `INDEXED/SUCCESS`；发布 claim 按 delay 节流 | Java claim 单测；`scripts/test_rabbitmq_reliability.sh` | 自动测试已通过；演练脚本已就绪 |
| R03 | FastAPI 不可用 | Python 不可用时，失败状态落库可见 | 停 FastAPI 后上传文档 | `document=INDEX_FAILED`、`task=FAILED` | `scripts/test_index_retry.sh` 前半段 | 演练脚本已就绪 |
| R04 | 人工重试索引 | 失败后新增 attempt，历史失败证据不被复活 | 恢复 FastAPI 后调用 `/index-retry` | attempt 0 保持 FAILED；attempt 1 为 `MANUAL_RETRY/PENDING -> RUNNING -> SUCCESS`；document current pointer 切换 | Java 单测；`scripts/test_index_retry.sh` | 自动测试已通过；演练脚本已就绪 |
| R05 | 旧 worker 迟到返回 | timeout/retry 后旧 attempt 不能覆盖 current MySQL 状态 | 用确定性 CAS 序列模拟旧 worker 晚到成功/异常 | 旧 task/document 终态 CAS no-op或事务回滚；不承诺旧调用完全不触碰 Qdrant | `IndexingServiceImplTest`、`DocumentStatusTimeoutSchedulerTest` | 自动测试已通过；外部副作用不在保证内 |
| R06 | 删除与索引/删除冲突 | `PENDING_INDEX` / `INDEXING` 不能删除，`DELETING` 不能启动第二次删除 | RabbitMQ 停机形成 PENDING 后 DELETE；单测构造 DELETING | 返回 `409004 DOCUMENT_BUSY` | Java 单测；`scripts/test_rabbitmq_reliability.sh` | 自动测试已通过；演练脚本已就绪 |
| R07 | 删除 Qdrant vectors 失败 | 删除开始后业务不可见，且只由本 generation 写失败态 | 已索引后停止 Qdrant，再 DELETE | `is_deleted=1`、`index_status=DELETE_FAILED`；恢复后再次 DELETE 认领新 generation | Java 异常/generation 单测；`scripts/test_document_delete_failure_recovery.sh qdrant` | 自动测试已通过；演练脚本已就绪 |
| R08 | 删除 MinIO object 失败 | MinIO 删除失败不让文档重新可见，旧 generation 不覆盖新重试 | 已索引后停止 MinIO，再 DELETE | vectors 已幂等删除；本 generation 进入 `DELETE_FAILED`；恢复后再次 DELETE 成功 | Java 异常/generation 单测；`scripts/test_document_delete_failure_recovery.sh minio` | 自动测试已通过；演练脚本已就绪 |
| R09 | 删除后同内容重传 | deleted 历史 checksum 不阻塞同内容再次上传和再次删除 | 同内容文档上传、删除、重传、再删除 | 第二次上传和删除均成功 | `scripts/test_document_reupload_delete.sh` | 演练脚本已就绪 |
| R10 | 删除后 Qdrant 残留防线 | 即使 Qdrant 残留 vectors，Java 返回前也按 MySQL 二次过滤 | Qdrant 删除失败后恢复服务、但在重试 DELETE 前检索同一标记 | 控制文档保证检索真实下游调用；目标 docId/chunk 不返回 | `scripts/test_document_delete_failure_recovery.sh qdrant` | 演练脚本已就绪 |
| R11 | 跨用户检索隔离 | Alice/Bob 不能检索或 RAG 到对方知识库内容 | Alice/Bob 各自上传私有文档并互查 | 直接访问对方 kb 返回 404；本库检索不出现 forbidden terms | 自包含 fixture；`scripts/test_retrieval_permission.sh`、`scripts/test_rag_permission.sh` | 演练脚本已就绪 |
| R12 | 空知识库 RAG 短路 | 无已索引 chunk 时不调用 LLM | 空知识库调用 RAG | `answerStatus=NO_CONTEXT`、`noAnswer=true`、原因非空，citations/retrievedChunks 为空 | Java 单测；`scripts/test_rag_empty_kb.sh` | 确定性测试已纳入统一入口；演练脚本已就绪 |
| R13 | 并发上传触发 Qdrant schema 初始化 | 两个索引任务接近同时写 Qdrant 时，不应因 collection 或 payload index 已存在而失败 | 并发上传两份文档；首次 schema 初始化压力验证需在一次性环境先清空 collection | 两个文档均 `INDEXED/SUCCESS`，两个唯一标记均可检索 | 手工步骤，当前无脚本 | 待验证 |
| R14 | 同知识库并发同内容上传 | 两个请求同时上传相同文件时，只能创建一个 active document，失败请求不应先写出额外 MinIO object | 同一用户同一知识库并发上传同一文件 | 一条成功，一条 `409003 DOCUMENT_ALREADY_EXISTS`；成功文档可索引 | 历史记录；当前无脚本 | 待重建证据 |
| R15 | 索引 timeout 原子性 | current task/document 必须同时进入失败态 | 构造 `RUNNING + INDEXING` 且 started_at 超时 | 同一短事务写 `FAILED + INDEX_FAILED`；任一 CAS 失败则整体回滚 | Java rollbackOnly 单测；真实 MySQL 事务验证待补 | 自动测试已通过；真实事务待验证 |
| R16 | 并发人工 retry | 同一 FAILED attempt 只能创建一个后继 attempt | 两个请求并发调用 `/index-retry` | 唯一约束 + current pointer CAS 只允许一个赢家，无孤立新 task | 唯一约束设计与顺序单测；真实 MySQL 并发验证待补 | 部分验证 |
| R17 | 知识库删除与文档上传 | “无活动文档检查”不能被并发上传穿透 | 同一 KB 并发 DELETE 与上传 | 两条写路径按 knowledge_base 行锁串行；要么删除冲突，要么上传得到 NOT_FOUND | 真实 MySQL 并发验证待补 | 设计落地 |
| R18 | MinIO 写入后的事务回滚 | 数据库明确回滚时不留下本次孤儿 object，同时不误判 commit outcome unknown | 在 MinIO put 后注入事务 rollback / unknown | ROLLED_BACK 按 objectKey 清理；UNKNOWN 只告警并保留 object | 代码路径；事务故障注入待补 | 设计落地 |
| R19 | RAG no-answer 与实际引用协议 | 候选 chunk 不得全部伪装成 citation；拒答必须结构化 | 构造有效/重复/越界/缺失引用、纯 sentinel 与混合 sentinel 响应 | `ANSWERED` 只映射实际有效引用；`INSUFFICIENT_CONTEXT` 引用为空；无效协议返回 AI 服务失败；outcome 为 `answered/no_context/insufficient_context/failed` | Java/Python 单测；`scripts/test_rag_ask.sh` | 确定性测试已纳入统一入口；真实 LLM 回归待执行 |

## 手动验证顺序

推荐按从稳定到破坏性排序执行：

```bash
# 以下命令都从仓库根目录执行。

# 0. 先执行不启动服务的确定性核心检查
./scripts/verify.sh

# 1. 权限、删除残留和基本检索隔离
./scripts/test_retrieval_permission.sh

# 2. 同内容文档删除后重传
./scripts/test_document_reupload_delete.sh

# 3. 知识库删除保护
./scripts/test_knowledge_base_delete_guard.sh

# 4. RAG 正常回答、权限和空知识库短路
./scripts/test_rag_ask.sh
./scripts/test_rag_empty_kb.sh
./scripts/test_rag_permission.sh

# 5. FastAPI 不可用后的失败落库与人工重试
./scripts/test_index_retry.sh

# 6. RabbitMQ 停机、自动重投、重复消息与 DLQ
# 先在当前会话安全导出 RABBITMQ_USERNAME / RABBITMQ_PASSWORD。
MYSQL_DEFAULTS_FILE=... ./scripts/test_rabbitmq_reliability.sh

# 7. 删除外部依赖失败与恢复；两个模式分开执行
MYSQL_DEFAULTS_FILE=... ./scripts/test_document_delete_failure_recovery.sh qdrant
MYSQL_DEFAULTS_FILE=... ./scripts/test_document_delete_failure_recovery.sh minio
```

`MYSQL_DEFAULTS_FILE` 指向本地 MySQL client 配置文件；脚本只执行固定的只读状态查询，不打印数据库或 RabbitMQ 凭证。两条 P0-3 故障脚本不会自行启停服务，会在注入点等待操作者停止/恢复对应本地依赖。外部演练实际执行并保存日期与关键输出前，状态只能写“脚本已就绪”。R13、R14 仍无当前脚本，不把历史文件名当作证据。

正常 RAG 脚本会调用真实 LLM，只有配置、网络和模型响应都满足当前结构化协议时才有意义；它不纳入核心 CI。`NO_CONTEXT` 的 Java 短路、模型 sentinel 对应的 `INSUFFICIENT_CONTEXT`、有效/无效引用映射已经有确定性测试，但仍不能替代真实跨进程调用记录。

外部演练完成后，按下面最小字段追加记录，不保存 token、密码、完整问题或文档正文：

```text
日期 / 环境：
Git commit：
场景编号与命令：
关键业务 ID：documentId / taskId（仅本地测试数据）
故障前状态：
故障期间状态与错误码：
恢复后状态：
计数证据：attempt / retry / model_call_log / DLQ（按场景选择）
结论与未覆盖边界：
```

## 已验证记录

### 2026-07-17（P1 资产状态，不是外部演练记录）

仓库已加入统一 `scripts/verify.sh`、双端 DTO fixture、loader/splitter/Qdrant/固定数据集测试、结构化 no-answer/实际引用测试以及本机 Compose 配置。当前工作树实际执行结果为 Java 62 个测试、Python 30 个测试及 shell 语法检查通过，Compose 静态解析通过。本文没有记录 Compose 镜像构建、容器启动、真实 LLM RAG 或端到端故障演练结果；这些操作实际执行前只能描述为“配置/脚本已就绪”。

### 2026-07-16

P0-3 开发阶段执行以下确定性自动测试：

```bash
cd enterprise-rag-backend
mvn -q -o test
```

结果：全量 55 个测试通过，0 failures、0 errors、0 skipped。其中本轮新增 9 个测试，覆盖 current SUCCESS 重复消息不调用 AI、非法 RabbitMQ JSON reject、晚到索引成功/异常的 stale CAS、terminal document CAS 丢竞争时 rollbackOnly、timeout task CAS 丢竞争、删除 timeout generation 条件、Qdrant/MinIO 删除失败的 `DELETE_FAILED + is_deleted=1`，以及 `DELETE_FAILED` 重试 generation 单调递增后进入 `DELETED`。

同时新增 RabbitMQ 与删除依赖故障演练脚本，并修复权限脚本依赖未跟踪 `/tmp` fixture 的问题；这些 shell 文件已通过 `bash -n`，但本记录不宣称 RabbitMQ/Qdrant/MinIO 外部停机演练已经执行。

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
| RabbitMQ 发布失败后 PENDING 重投 | `IndexingServiceImpl` 发布失败不改写 MySQL 失败态，`PendingIndexingTaskRepublishScheduler` 会扫描 PENDING 任务重新发布。 | 停 RabbitMQ 上传后恢复，不调用 `/index-retry`，等待 scheduler 自动重投。 |
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

以下场景仍需真实依赖证据或更高层级验证：

- R01/R02/R06：RabbitMQ 演练脚本已就绪，仍需在本地真实停机/恢复后记录 taskId、attempt、Java→Python `document_index` HTTP 尝试数、模型日志数和 DLQ 增量。
- R07/R08/R10：Qdrant/MinIO 演练脚本已就绪，仍需分别执行并记录 `DELETE_FAILED -> DELETED`、generation 和检索过滤结论。
- R05：确定性 Java 测试只证明 MySQL current task fencing。当前不为此增加慢 worker 后门、Toxiproxy 或 task-aware vector 版本；旧 Python 调用可能触碰 Qdrant 的风险继续明确保留。
- R15/R16/R17：事务回滚、并发 retry 和知识库行锁仍需真实 MySQL 并发测试；Mockito 单测不能替代数据库约束证据。
- R18：ROLLBACK / UNKNOWN 两类事务完成状态仍需可控故障注入；UNKNOWN 无法安全自动判定，不承诺零孤儿。
- Qdrant 残余：旧 Python 调用最后执行时可能覆盖或删除 current vectors；完整解决需要 task-aware point/version、current-attempt 过滤与旧版本清理，本阶段不扩展。

这些缺口不是当前主链路阻塞点。面试时应该说明：当前选择状态驱动和人工重试，而不是宣称已经具备生产级自动补偿系统。
