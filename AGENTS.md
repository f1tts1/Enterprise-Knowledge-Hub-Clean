# AGENTS.md

本文件约束本仓库后续开发。任何自动化代理或协作者在修改代码前都必须阅读，并以当前已落地实现为准。

## 项目定位

Enterprise Knowledge Hub 是一个面向 Java 后端 / AI 工程化求职展示的企业知识库项目，目标是做真实工程闭环，而不是堆叠框架：

- Spring Boot 负责业务系统、鉴权、权限边界、文档元数据、任务状态和服务编排。
- FastAPI 负责文档解析、文本切分、本地 embedding、Qdrant 访问和受控 RAG 生成。
- RabbitMQ 承担上传到索引处理的异步任务队列。
- Redis 承担认证用户快照、Refresh Token 会话和知识库访问缓存。
- MinIO 保存原文件，MySQL 保存业务事实，Qdrant 保存 chunk vector 与检索 payload。

当前链路已经落地为：上传 -> 异步索引 -> Qdrant -> 权限过滤检索 -> Java 编排同步 RAG -> 结构化拒答/实际引用 -> 删除向量。当前不实现 SSE、多轮会话落库、hybrid/rerank/query rewrite、正式 RAG 评测平台或 Agent；固定问题集只作为轻量回归基线。

## 技术栈

- Java 17、Spring Boot 3.3.5、MyBatis-Plus 3.5.9。
- Spring Security、JWT、BCrypt、WebClient/Reactor Netty。
- Actuator、Micrometer、Prometheus registry。
- Python 3.11+、FastAPI、PyMuPDF、python-docx。
- sentence-transformers、本地 `BAAI/bge-small-zh-v1.5`。
- MySQL、Redis、RabbitMQ、MinIO、Qdrant。

## 目录职责

```text
enterprise-rag-backend/src/main/java/com/example/ekb/
  auth/          注册、登录、JWT
  user/          当前用户
  knowledge/     个人知识库 CRUD
  document/      上传、查询、状态驱动删除
  indexing/      不可变 attempt、current-task fencing、RabbitMQ
  retrieval/     Java 权限校验、Python 检索、MySQL 二次过滤
  rag/           最小同步 RAG 编排与对外状态
  ai/            Java 调 FastAPI 的 HTTP client / DTO
  storage/       MinIO 抽象
  security/      JWT 认证
  common/        统一响应、错误码、requestId
  observability/ requestId、低基数指标、model_call_log

rag-ai-service/app/
  api/v1/        索引、检索、生成内部 API
  clients/       MinIO / Qdrant
  document_loader/ splitter/ embedding/
  indexing/ retrieval/ generator/
  observability/ schemas/

contracts/ai/    Java/Python 共用 DTO fixture
eval/rag/        固定轻量回归资产；results 被忽略
docs/            架构、契约、测试、部署与证据
scripts/         统一验证、回归与故障演练
```

## 编码规范

- Java 业务服务使用接口 + `impl`，Controller 只收参和封装响应，业务放 Service。
- 对外响应统一使用 `ApiResponse<T>`；分页统一使用 `PageResponse<T>`；错误码维护在 `ErrorCode`。
- 跨模块业务状态常量放 `common/constants`；不要为局部值制造全局常量层。
- Java 与 Python 只通过 HTTP DTO 通信，Java 不执行 Python 脚本或本地进程。
- Python DTO 使用 Pydantic；索引 pipeline 保持“下载、解析、切分、embedding、写 Qdrant”的显式步骤。
- 注释使用中文，解释工程原因、边界和风险；禁止空文件、空类、只有 docstring 的占位实现。
- 修改接口、状态、错误码、权限、异步流程、向量 schema 或 Compose 环境变量时，同步更新 `docs/API_CONTRACT.md`、`docs/STATUS.md` 和必要 README。
- 可观测性是旁路：指标和 `model_call_log` 失败不能改写成功主链路。
- Micrometer 标签只使用有限枚举；禁止 requestId、userId、kbId、documentId、taskId、正文或错误全文进入标签。
- 日志和模型调用记录不得保存 API Key、Authorization、完整 prompt、问题、chunk 或答案正文；错误需脱敏和截断。

## 架构约束

### 业务与 AI 边界

- Spring Boot 是业务主体；FastAPI 不成为第二套用户/知识库业务后端。
- 用户、知识库和文档归属必须由 Java 校验；Python 只接收 Java 已校验的最小 DTO。
- 检索先由 Java 校验 owner，Python 必须把 `ownerUserId + kbId` 下推到 Qdrant filter，Java 再按 MySQL 当前状态二次过滤。
- 文件通过 MinIO 传递，Java 不把文件字节同步转发给 Python。

### 异步索引

- 上传后不得同步调用 Python，必须经 RabbitMQ。
- RabbitMQ 消息只包含 `documentId` 和 `indexingTaskId`，消费者重新查询 MySQL。
- 消费者校验 task/document 的 `documentId + kbId + ownerUserId`；合法历史消息跳过，静态关联错误进入 DLQ。
- 每个 `indexing_task` attempt 单向流转；FAILED 业务重试新建 task，禁止重置为 PENDING。
- 只有 PENDING 传输恢复可重投同一 task；`last_publish_attempt_at` 必须原子 claim 和节流，`max_retry` 必须生效。
- `document.current_indexing_task_id` 是 fencing token；成功、失败、timeout 都必须匹配 current task。
- 文档索引状态仅使用 `PENDING_INDEX`、`INDEXING`、`INDEXED`、`INDEX_FAILED`、`DELETING`、`DELETED`、`DELETE_FAILED`。
- Python 写 Qdrant 后只回报 DTO，不修改 MySQL。

### 删除一致性

- Java 驱动删除状态，Python 仅按 `ownerUserId + kbId + docId` 删除 Qdrant points。
- 成功、失败、timeout 都必须匹配本次 `delete_generation`；旧调用不能覆盖新重试。
- Qdrant/MinIO 不参与 MySQL 分布式事务，失败进入可见状态并允许受控重试，不伪装强一致。

### RAG 协议

- `/retrieval/search` 是独立向量检索，不得描述成完整 RAG。
- RAG 必须复用 Java 检索服务的 owner 校验、Qdrant filter 与 MySQL 二次过滤，再将上下文发给 Python。
- Python 生成只消费已过滤上下文，不访问 MySQL，也不执行完整用户权限判断。
- 对外状态只使用：
  - `ANSWERED`：至少一个合法实际引用。
  - `NO_CONTEXT`：Java 无候选/无正文短路，不调用 LLM。
  - `INSUFFICIENT_CONTEXT`：阈值过滤为空或模型按 sentinel 拒答。
- Python 只把完整 `__EKB_NO_ANSWER__` 解析成拒答；与其它文本混用、无引用回答、越界引用都视为 provider 协议错误。
- `citations` 仅包含答案实际出现的去重 `[片段 n]`；`retrievedChunks` 是候选结果，两者不得混称。
- `RAG_MINIMUM_RELEVANCE_SCORE` 默认 `-1` 禁用。历史 fixed baseline 中正负样本分数重叠，未经重跑实验不得启用或宣传统一阈值效果。
- `charStart`/`charEnd` 是 splitter 在页面文本规范化和 overlap 后生成的逻辑 span，0-based、end-exclusive；当前递归边界重组使它不保证是 loader 原文的严格切片，也不是文件字节偏移或 PDF 排版坐标。引用以 `pageNo + chunkIndex + text` 为主。

### 关联与指标

- 同步入口使用安全 `X-Request-Id` 关联 Java、Python、LLM；非法/缺失值生成 UUID。
- 异步索引不沿用上传 ID，使用 `index-task-{indexingTaskId}`；消息体仍只有两个业务 ID。
- Python 阶段耗时/usage 仅用于可观测性，不参与权限或状态判断。
- RAG outcome 使用 `answered`、`no_context`、`insufficient_context`、`failed`。
- 非流式 LLM 完整耗时不得称为 TTFT。
- `/actuator/prometheus` 继续受 JWT 保护；不得匿名放行，也不得恢复 health 演示接口。

## 构建、运行与测试

命令默认从仓库根目录执行。

确定性核心验证：

```bash
python -m pip install -r rag-ai-service/requirements-test.txt
./scripts/verify.sh
docker compose --env-file .env.example config --quiet
```

`verify.sh` 不启动应用或外部依赖。完整证据分层见 `docs/TESTING.md`。

Java 单独编译：

```bash
cd enterprise-rag-backend
mvn -q -DskipTests -o compile
```

Python 语法检查：

```bash
python3 -m compileall rag-ai-service/app scripts/download_embedding_model.py scripts/run_rag_eval.py
```

Compose 本机演示：

```bash
cp .env.example .env
# 替换所有 secret 占位符
docker compose up --build
```

首次模型初始化依赖网络；宿主端口只绑定 `127.0.0.1`。`docker compose down -v` 会删除所有数据卷和模型卷。完整边界见 `docs/DEPLOYMENT.md`。

非容器环境的数据库路径互斥：空/可重置库只执行 V1；旧库备份后按缺失版本执行 V2/V3。V1 会删除数据库，V3 非幂等，不能无确认重跑。

本地完整环境测试：

```bash
./scripts/test_retrieval_permission.sh
./scripts/test_document_reupload_delete.sh
./scripts/test_rag_ask.sh
./scripts/test_rag_empty_kb.sh
./scripts/test_rag_permission.sh
python3 scripts/run_rag_eval.py --retrieval-only
```

生成脚本需要 FastAPI 已配置 LLM。故障演练命令和证据口径见 `docs/RELIABILITY_MATRIX.md`；脚本存在不等于外部演练通过。

## 当前演示路径

1. 注册并登录。
2. 创建个人知识库。
3. 上传 TXT/Markdown/PDF/DOCX。
4. 观察 document/task 到 `INDEXED/SUCCESS`。
5. 在 Java 检索入口查询 chunk。
6. 调用同步 RAG，观察结构化状态、actual citations 和 candidate chunks。
7. 使用另一用户验证知识库 404 / 无越权检索。
8. 删除文档，确认不再召回对应 Qdrant chunk。

## 明确禁止事项

- 禁止新增功能而不更新状态和契约文档。
- 禁止恢复 health API，或为了演示/测试增加生产 test-only API。
- 禁止把基础指标描述为 Grafana、告警、长期存储或生产级监控。
- 禁止把完整 LLM 耗时描述成 TTFT，把调用日志字段描述成正式 RAG 评测系统。
- 禁止把未实现的 SSE、多轮会话、Agent、hybrid、rerank、query rewrite 或正式评测写成已完成。
- 禁止把 candidate `retrievedChunks` 全部伪装成 citations。
- 禁止把历史 baseline 当作当前协议的重新执行证据。
- 禁止把 Compose 静态解析描述成镜像已构建、服务已启动、一键部署已验证或生产部署。
- 禁止让 Python 成为完整业务后端，或绕过 Java 权限访问任意 bucket/object。
- 禁止缺失 Qdrant 的 `ownerUserId + kbId` filter。
- 禁止提交 `.models/`、`.env`、API Key、JWT secret、MinIO/Redis/RabbitMQ/MySQL 密码。
- 禁止为了技术栈数量引入 Kafka、Elasticsearch、Kubernetes、服务网格或注册中心。
