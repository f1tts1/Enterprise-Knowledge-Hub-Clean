# 当前实现状态

更新时间：2026-07-17

## 总结

项目已完成求职版本的核心业务、RAG 工程闭环、P0 可靠性/可观测性，以及本轮 P1-1、P1-2、P1-3 的代码与文档开发。下一步不应继续堆业务功能，而应实际执行 Compose 演示和关键故障演练、保存证据，然后转向简历、演示和源码复盘。

本轮证据边界：`./scripts/verify.sh` 已实际通过（Java 62 个测试、Python 30 个测试及全部 shell 语法检查），`docker compose --env-file .env.example config --quiet` 已通过；没有启动服务、拉取/构建镜像、部署或执行端到端测试。历史 baseline 不是当前协议的重新执行结果。

## 已完成主链路

### 认证与知识库

- 注册、登录、JWT access token、Redis refresh token 会话和登出撤销。
- 当前用户查询、个人知识库 CRUD、owner 权限边界与访问缓存。
- Controller / Service / Mapper 分层、统一响应与错误码。

### 文档与异步索引

- Java 校验文件类型/大小/归属，将原文件写入 MinIO，元数据写入 MySQL。
- 每次索引 attempt 是不可变 `indexing_task`；RabbitMQ 消息仅含 `documentId + indexingTaskId`。
- `document.current_indexing_task_id` 防止历史 worker 覆盖 current 状态。
- PENDING 发布原子 claim、节流、最大重试；消费者 retry/DLQ；业务 FAILED 重试新建 attempt。
- Python 从 MinIO 下载，解析 TXT/Markdown/PDF/DOCX，按页面切分，生成本地 BGE embedding，upsert Qdrant。
- Qdrant point ID 对同一 doc/chunk 稳定，payload 保留权限与引用元数据。

### 删除与跨存储一致性

- 删除由 Java 状态机驱动，按 `delete_generation` fencing。
- Python 按 `ownerUserId + kbId + docId` 幂等清理 Qdrant，Java 再清理 MinIO并更新 MySQL。
- Qdrant/MinIO 失败进入 `DELETE_FAILED` 并允许受控重试；旧调用不能覆盖新 generation。
- 项目公开承认 MySQL、MinIO、Qdrant 不存在分布式事务，使用状态、幂等、fencing 和恢复流程收敛。

### 权限检索

- Java 先校验当前用户拥有知识库。
- Python 将 `ownerUserId + kbId` 下推为 Qdrant filter。
- Java 根据 MySQL 中 document 当前 owner、kb、`INDEXED` 和未删除状态二次过滤，并 over-fetch 弥补过滤损耗。
- 删除后不应再通过检索/RAG 暴露对应 chunk。

### 最小同步 RAG

- Java 复用权限检索结果，Python 只消费已过滤上下文并调用 OpenAI-compatible Chat Completions。
- 对外响应包含 `ANSWERED`、`NO_CONTEXT`、`INSUFFICIENT_CONTEXT` 三种结构化状态，以及 `noAnswer`/`noAnswerReason`。
- 无结果/无可用正文由 Java 短路，不调用 LLM。
- Python 使用完整 sentinel `__EKB_NO_ANSWER__` 表示模型拒答；混用文本视为协议失败。
- answered 响应必须包含合法 `[片段 n]`；无引用或越界引用视为 AI provider 契约错误。
- `citations` 只映射实际引用且按首次出现去重；`retrievedChunks` 保留全部权限过滤候选。
- 可配置 `RAG_MINIMUM_RELEVANCE_SCORE`；默认 `-1` 关闭，因为历史 fixed baseline 中正负题分数重叠。
- 控制台展示回答状态、拒答原因、实际引用数和候选 chunk。

### 可观测性

- 同步入口安全生成/接收 `X-Request-Id`，传播至 FastAPI 和外部 LLM。
- 异步 attempt 使用 `index-task-{indexingTaskId}`。
- Python 返回索引、检索、生成阶段耗时和 provider usage。
- Java 暴露低基数 Micrometer 指标，RAG outcome 为 `answered`、`no_context`、`insufficient_context`、`failed`。
- `model_call_log` best-effort 写入 provider/model/type/token/latency/success/errorType，不保存 prompt、问题、chunk 或答案。
- 可观测性失败不改写业务成功；`/actuator/prometheus` 仍受 JWT 保护。

## P0 完成情况

### P0-1 异步 attempt、幂等与失败恢复

已完成不可变 attempt、current-task fencing、PENDING 重投、timeout、消费 retry/DLQ、删除 generation fencing 和相关确定性测试。

### P0-2 requestId、调用日志与基础指标

已完成跨服务 requestId、阶段耗时、usage、低基数 metrics 与 `model_call_log` 旁路。未实现 Grafana、告警、长期存储或正式评测平台。

### P0-3 故障演练与证据固化

已完成可靠性单元测试和 RabbitMQ/Qdrant/MinIO 交互式脚本。脚本已就绪不等于外部故障已经演练；实际执行前不得写“故障恢复验证通过”。

## 本轮 P1 完成情况

### P1-1 自包含自动化测试与文档证据治理

- 新增 `scripts/verify.sh` 统一执行 shell 语法、Java 测试和 Python 核心测试。
- 新增 GitHub Actions，在 PR、main push 和手动触发时运行统一入口并静态解析 Compose。
- Python 核心测试覆盖 TXT/Markdown/PDF/DOCX loader、splitter、Qdrant 权限/删除 filter、payload、稳定 point ID。
- 恢复并修正 `eval/rag` 的 10 份文档、50 条问题与配置；结果目录保持忽略。
- 新增 Java/Python 共用 `contracts/ai` fixture，两端同时验证序列化/反序列化。
- 所有仓库文档和命令使用相对路径，不依赖单个开发者目录或 `/tmp` fixture。

当前可以证明确定性测试存在并可运行；不能由此证明真实 MySQL 并发、RabbitMQ 网络故障或 LLM 质量。

### P1-2 最小 no-answer 与真实引用语义

- 完成结构化 answer status、受控 sentinel、引用解析/去重/越界校验。
- 完成空上下文、低阈值上下文、模型拒答与 provider 协议失败分流。
- 完成 Java 实际 citation 映射、前端展示、指标 outcome 和单元测试。
- 评测脚本改为使用结构化状态，并拆分检索、候选 chunk、citation、answer source 与 question echo。

这不是复杂 groundedness/no-answer 模型，也不包含 hybrid/rerank/query rewrite。统一相似度阈值默认关闭，启用前必须重跑当前固定集。

### P1-3 Docker Compose 可复现演示环境

- 新增根 `compose.yaml`、`.env.example`、`.dockerignore` 和 Java/Python Dockerfile。
- 编排 MySQL、Redis、RabbitMQ、MinIO、Qdrant、model-init、FastAPI 和 Spring Boot。
- 使用命名卷保留中间件数据和 embedding 模型；模型对运行服务只读。
- 宿主端口绑定 `127.0.0.1`；FastAPI 未增加虚假内部鉴权或匿名 health API。
- Java AI service URL 支持环境变量覆盖。

当前只完成配置静态解析；本轮没有镜像拉取/构建、容器启动或 E2E 证据，不能称为生产部署或“已验证一键启动”。

## 自动化测试资产

- Java：状态机、fencing、超时、删除失败、RAG 状态/引用、可观测性与 DTO fixture。
- Python：pipeline metrics、loader/splitter、Qdrant contract、生成协议、eval dataset、DTO fixture。
- Shell：权限、重传删除、RAG、RabbitMQ 和删除故障演练。
- CI 不下载模型、不启动中间件、不调用真实 LLM。

准确命令和证据含义见 `TESTING.md`。

## 固定 RAG 基线

- 当前资产为 10 份文档、50 条问题，覆盖 single fact、multi-hop、paraphrase、no-answer 和 permission。
- `run_rag_eval.py` 可运行 retrieval-only 或完整生成回归。
- 文档记录的 2026-06 历史摘要使用旧 RAG 引用/拒答协议，只作历史参考；原始 results 不随 Git 提交，当前协议必须另行重跑。
- 本阶段仍不建设正式 RAG 评测/实验平台。

## 已知限制与残余风险

- Java→FastAPI 没有独立服务间认证；Compose 仅将端口绑定本机，真实跨机部署前必须重新设计信任边界。
- Qdrant 成功而 MySQL current 状态更新失败时可能残留 vectors；Java 二次过滤避免暴露，但仍需后续清理。
- 迟到 Python worker 的外部 Qdrant side effect 不能被 MySQL fencing 阻止；fencing 只保证旧 worker 不覆盖 current 业务状态。
- 删除流程不是分布式事务，最终恢复依赖失败状态和人工/业务重试。
- 默认 dense retrieval 无 hybrid/rerank/query rewrite；这是当前阶段主动取舍。
- no-answer 是最小工程协议，不证明语义判断在开放问题上稳定。
- 非流式生成只记录完整 LLM 调用耗时，不提供 TTFT。
- Compose 镜像 tag、依赖范围、首次模型下载和不同 CPU 架构尚待实际 build/up 验证。
- V1 SQL 含破坏性 DROP，只能用于空/可重置数据库；旧库迁移需备份且 V3 非幂等。

## 明确未完成且当前不开发

- SSE 与多轮会话/消息/引用落库。
- Agent 或 LangGraph 工作流。
- hybrid search、rerank、query rewrite。
- 正式 RAG 评测/实验平台。
- Grafana dashboard、告警和长期指标存储。
- Kubernetes、Helm、服务网格、注册中心。
- Kafka、Elasticsearch等重复中间件。

数据库中仍保留 `conversation`、`chat_message`、`answer_citation`、`feedback` 表，但当前没有对应业务 API，不能写入已完成功能。

## 下一步与停止条件

1. 在一台干净本机实际执行 Compose build/up，记录版本、耗时、服务状态和主链路结果。
2. 执行权限回归与至少一次 RabbitMQ、Qdrant/MinIO 故障演练，保存日期和关键输出。
3. 如要宣传当前 RAG 指标，使用当前协议重新运行固定集；否则只展示工程链路，不引用历史数字。
4. 完成上述证据后停止增加功能，转向 README 截图/演示、架构决策、简历表述、常见面试问题和源码复盘。
