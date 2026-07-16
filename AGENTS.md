# AGENTS.md

本文件用于约束本仓库后续开发。任何自动化代理或协作者在修改代码前，都应该先阅读本文件，并以当前已落地实现为准。

## 项目定位

Enterprise Knowledge Hub 是一个面向 Java 后端 / AI 工程化求职展示的企业知识库项目。

当前目标不是做 LangChain Demo，而是做一个真实后端工程闭环：

- Spring Boot 负责业务系统、鉴权、权限边界、文档元数据、任务状态和服务编排。
- FastAPI 负责文档解析、文本切分、本地 embedding、Qdrant 写入和知识库内向量检索。
- RabbitMQ 承担文档上传后到索引处理之间的异步任务队列。
- Redis 承担认证用户快照缓存、Refresh Token 会话和知识库访问缓存。
- MinIO 存储上传文件。
- MySQL 保存用户、知识库、文档、索引任务等业务数据。
- Qdrant 保存 chunk 向量和检索所需 payload。

当前已经落地到“上传文档 -> 异步解析索引 -> 写入 Qdrant -> 按用户和知识库过滤检索 chunk -> Java 编排最小同步 RAG 问答 -> 删除文档时清理 Qdrant vectors”。当前 RAG 只做同步问答和引用片段返回，不实现 SSE 流式输出、会话引用落库、复杂 no-answer 判断、正式 RAG 评测系统、rerank 或 Agent 工作流；仓库中的固定问题集只作为轻量回归基线。

## 技术栈

- Java：Java 17 + Spring Boot 3.3.5
- Java ORM：MyBatis-Plus 3.5.9
- Java 安全：Spring Security + JWT + BCrypt
- Java HTTP 客户端：Spring WebClient / Reactor Netty
- Java 可观测性：Spring Boot Actuator + Micrometer + Prometheus registry
- Python：Python 3.11+ + FastAPI
- Python 文档解析：PyMuPDF、python-docx、自定义 TXT/Markdown loader
- Python embedding：sentence-transformers + 本地 `BAAI/bge-small-zh-v1.5`
- 数据库：MySQL 8.0+ / 9.x
- 异步队列：RabbitMQ
- 缓存 / 登录会话：Redis
- 对象存储：MinIO
- 向量库：Qdrant

## 目录职责

```text
enterprise-rag-backend/
  src/main/java/com/example/ekb/
    auth/        用户注册、登录、JWT 签发
    user/        当前用户信息
    knowledge/   个人知识库 CRUD
    document/    文档上传、文档列表、文档详情、索引状态、状态驱动删除
    indexing/    不可变索引 attempt、current task fencing、RabbitMQ 生产和消费
    retrieval/   知识库内向量检索入口，负责 Java 层权限校验
    rag/         最小同步 RAG 问答入口，复用检索并调用 Python 生成
    ai/          Java 调 Python AI 服务的 HTTP client 和 DTO
    storage/     MinIO 存储抽象与实现
    security/    JWT 认证过滤器和 token provider
    common/      统一响应、错误码、异常处理、requestId
    config/      Spring Security、WebClient、MinIO 配置
    observability/ requestId 下游传播、低基数指标和 model_call_log 旁路写入
  src/main/resources/
    application.yml
    db/migration/V1__init_schema.sql
    static/console/  Spring Boot 同源提供的前端应用

rag-ai-service/
  app/
    main.py              FastAPI 应用入口
    api/v1/documents.py  文档索引内部 API
    api/v1/retrieval.py  向量检索内部 API
    api/v1/rag.py        RAG 生成内部 API
    clients/             MinIO 读取客户端、Qdrant 写入/检索/删除客户端
    config/              环境变量配置
    document_loader/     PDF、Word、TXT、Markdown 解析
    splitter/            文本切分与 chunk 元数据
    embedding/           本地 embedding provider
    indexing/            文档索引 pipeline：解析、切分、embedding、写入 Qdrant
    retrieval/           query embedding + Qdrant filter 检索
    generator/           OpenAI-compatible Chat Completions 答案生成
    observability/       requestId 上下文、中间件和日志配置
    schemas/             Python API DTO

docs/
  ARCHITECTURE.md
  STATUS.md
  DECISIONS.md
  API_CONTRACT.md
  OBSERVABILITY.md
  V1已实现功能测试用例.md

scripts/
  download_embedding_model.py
  test_retrieval_permission.sh
  test_document_reupload_delete.sh
  test_rag_ask.sh
  test_rag_empty_kb.sh
  test_rag_permission.sh
```

## 编码规范

- Java 业务服务使用接口 + `impl` 实现类，例如 `DocumentService` + `DocumentServiceImpl`。
- Java Controller 只做参数接收和响应封装，业务逻辑放在 Service。
- Java 对前端响应统一使用 `ApiResponse<T>`。
- Java 分页响应统一使用 `PageResponse<T>`。
- Java 错误码统一维护在 `ErrorCode`。
- Java 跨模块共享的业务状态常量统一维护在 `common/constants`，例如文档索引状态和索引任务状态；局部配置、限制值和错误文案不强行集中。
- Java 和 Python 通信必须通过 HTTP DTO，不允许 Java 直接执行 Python 脚本。
- Python API DTO 使用 Pydantic model。
- Python 的文档索引 pipeline 保持清晰步骤：下载文件、解析、切分、embedding、写入 Qdrant。
- Python 的检索 pipeline 保持清晰步骤：query embedding、Qdrant filter、返回 chunk 命中。
- 注释使用中文，重点解释工程原因、边界和风险，不给每行语法写空泛注释。
- 不要保留只有 docstring 的占位文件。文件存在就应该有真实职责。
- 修改接口、状态字段、错误码、异步流程、权限边界或向量库 schema 时，必须同步更新 `docs/API_CONTRACT.md`、`docs/STATUS.md` 和必要的 README。
- 可观测性是旁路：指标或 `model_call_log` 写入失败不得把已经成功的索引、检索或 RAG 主链路改写为失败。
- Micrometer 标签只能使用固定的 application 和 operation、outcome、failure_stage、phase、type 等有限枚举；禁止把 requestId、userId、kbId、documentId、taskId、模型正文或错误全文放进指标标签。
- 日志和模型调用记录不得保存 API Key、Authorization、完整 prompt、问题、chunk 或答案正文；错误信息必须做脱敏和长度限制。

## 架构约束

- Spring Boot 是业务系统主体；FastAPI 只处理 AI 能力和向量库访问细节。
- 用户权限、知识库归属、文档归属必须在 Java 层校验。
- Python 不负责完整用户权限判断，只接收 Java 已校验后的最小任务 DTO。
- 检索时 Java 必须先校验当前用户拥有知识库；Python 必须把 `ownerUserId + kbId` 下推到 Qdrant filter。
- 上传文档后不能同步调用 Python；必须通过 RabbitMQ 形成异步边界。
- RabbitMQ 消息只保存 `documentId` 和 `indexingTaskId`，消费者重新查 MySQL。
- 消费者必须校验 task/document 的 `documentId + kbId + ownerUserId` 关联；非 current 的合法历史消息跳过，静态关联错误进入 DLQ。
- 文件内容必须通过 MinIO 传递，Java 不把文件字节直接转发给 Python。
- 当前索引状态由 MySQL 中 `document` 和 `indexing_task` 维护。
- `indexing_task` 每个 attempt 单向流转；FAILED 业务重试必须新建 task，禁止把 FAILED task 重置为 PENDING。只有 PENDING 传输恢复允许重投同一 task。
- `document.current_indexing_task_id` 是索引 document 状态更新的 fencing token，task 成功、失败和 timeout 都必须匹配 current task。
- PENDING 发布重试必须通过 `last_publish_attempt_at` 做原子 claim 和时间节流；`max_retry` 必须实际生效。
- 当前 `document.index_status` 只使用 `PENDING_INDEX`、`INDEXING`、`INDEXED`、`INDEX_FAILED`、`DELETING`、`DELETED`、`DELETE_FAILED`，不保留当前流程不会写入的预留状态。
- Python 写 Qdrant 成功后只通过 DTO 汇报统计，不直接修改 MySQL。
- 删除文档时 Java 负责业务状态流转，Python 只按 Java 传入的 `ownerUserId + kbId + docId` 清理 Qdrant vectors。
- 删除成功、失败和超时必须匹配本次 `delete_generation`；旧删除调用不得覆盖新重试。
- 当前同时提供向量检索接口和最小同步 RAG 问答接口；不得把 `retrieval/search` 单独描述成 RAG 问答能力。
- RAG 问答必须复用 Java 检索服务完成知识库 owner 校验、Qdrant filter 检索和 MySQL 二次过滤后，再把 chunk context 传给 Python 生成。
- Python RAG 生成接口只消费 Java 已过滤的上下文，不访问 MySQL，不自行判断完整用户权限。
- 同步 Java API 采用入口 `X-Request-Id` 关联 Java、Python 和 LLM 调用；非法或缺失值由服务生成安全 UUID。异步索引不沿用上传请求 ID，而是使用稳定的 `index-task-{indexingTaskId}`，RabbitMQ 消息体仍只包含两个业务 ID。
- Python 索引、检索和生成响应中的阶段耗时/usage 只作为可观测字段，不参与权限或业务状态判断；当前非流式 LLM 耗时是完整调用耗时，禁止描述成首 token 延迟。
- `/actuator/prometheus` 继续受现有 Spring Security JWT 保护；不得为了抓取方便匿名放行，也不得恢复 health 接口作为演示入口。
- 当前不提供 `/health` 或 `/api/v1/health` 接口，连通性用真实业务接口验证。

## 构建、启动和测试命令

### MySQL 初始化

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/enterprise-rag-backend"
mysql -u root -p < src/main/resources/db/migration/V1__init_schema.sql
```

注意：当前 SQL 文件开头包含 `DROP DATABASE IF EXISTS enterprise_knowledge_hub;`，会删除本地已有数据。执行前必须确认这是本地测试库。

数据库初始化路径互斥：全新/可重置库只执行 V1（已含最终结构），不能再执行 V3；已有旧库不要重跑 V1，应先停应用并备份，再按需执行缺失迁移。V3 是一次性非幂等 DDL，失败后恢复备份，不直接重跑：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/enterprise-rag-backend"
mysql -u root -p < src/main/resources/db/migration/V2__fix_document_active_checksum_index.sql
mysql -u root -p < src/main/resources/db/migration/V3__add_indexing_attempt_and_deletion_fencing.sql
```

V3 会在 DDL 前校验旧实现“每个 document 至多一条 indexing_task”的不变量，异常数据必须人工确认后再迁移。

### Java 编译

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/enterprise-rag-backend"
mvn -q -DskipTests -o compile
```

如果需要联网拉依赖，去掉 `-o`。

### Java 启动

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/enterprise-rag-backend"
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
# 编辑 application-local.yml 并替换凭证占位值
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Java 默认依赖：

- MySQL：`localhost:3306`
- Redis：`localhost:6379`，凭证通过本地配置提供
- RabbitMQ：`localhost:5672`，凭证通过本地配置提供
- MinIO：`http://localhost:9000`，凭证通过本地配置提供
- FastAPI：`http://localhost:8000`
- Qdrant：`http://localhost:6333`

### Python 依赖

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
conda activate rag-ai-service
python -m pip install -r rag-ai-service/requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

### 下载本地 embedding 模型

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
conda activate rag-ai-service
python scripts/download_embedding_model.py
```

如 HuggingFace 直连较慢，可使用：

```bash
HF_ENDPOINT=https://hf-mirror.com python scripts/download_embedding_model.py
```

### Python 启动

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/rag-ai-service"
conda activate rag-ai-service
cp .env.example .env
# 编辑 .env 并替换 MinIO 凭证占位值
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### Python 语法检查

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
python3 -m compileall rag-ai-service/app scripts/download_embedding_model.py
```

### Qdrant 连通性

```bash
curl http://localhost:6333/collections
```

### 当前端到端权限检索测试

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
./scripts/test_retrieval_permission.sh
```

脚本会创建 Alice/Bob 两个测试用户、两个知识库，上传不同 TXT 文档，等待索引完成，并验证不同用户只能检索自己的知识库内容，以及删除文档后不会再召回该文档 chunk。

### 当前 RAG 验证脚本

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
./scripts/test_rag_ask.sh
./scripts/test_rag_empty_kb.sh
./scripts/test_rag_permission.sh
```

这些脚本只验证当前已实现的同步 RAG：正常问答、空知识库不调用 LLM 的兜底响应，以及 Alice/Bob 之间的 RAG 权限隔离。运行 RAG 生成相关脚本前，需要让 FastAPI 进程能读取 `DEEPSEEK_API_KEY` 或显式 `LLM_*` 配置。

### 当前可演示路径

1. 启动 MySQL、Redis、MinIO、Qdrant、FastAPI、Spring Boot。
2. 注册用户。
3. 登录获取 JWT。
4. 创建知识库。
5. 上传 TXT/Markdown/PDF/DOCX 文档。
6. 查询文档列表和索引状态，直到 `document.index_status=INDEXED`、`indexing_task.status=SUCCESS`。
7. 调用 Java 检索接口查询当前知识库 chunk。
8. 调用 Java RAG 问答接口，确认 answer、citations 和 retrievedChunks 基于当前用户知识库返回。
9. 用不同用户互查知识库，确认返回 404 或检索不到对方内容。
10. 删除文档，确认对应 Qdrant chunk 不再被检索返回。

## 明确禁止事项

- 禁止继续新增功能而不先更新状态文档。
- 禁止恢复早期 health 接口作为演示入口。
- 禁止把当前基础指标描述成已经具备 Grafana 看板、告警、长期指标存储或生产级监控。
- 禁止把同步 LLM 完整耗时描述成 TTFT，也不得把 P0-2 的调用日志字段预留描述成正式 RAG 评测系统。
- 禁止把未实现的 SSE、多轮会话落库、Agent、复杂 no-answer、rerank、正式评测平台写进“已完成”。
- 禁止把当前 retrieval/search 接口描述成已经完成 RAG 问答。
- 禁止把 Python 做成完整业务后端。
- 禁止在 Java 中直接调用 Python 脚本或本地进程。
- 禁止把本地模型目录 `.models/` 提交到 Git。
- 禁止把 DeepSeek/OpenAI API Key、JWT_SECRET、MinIO 密钥写死提交。
- 禁止绕过 Java 权限校验直接让 Python 按用户输入访问任意 bucket/object。
- 禁止让 Qdrant 检索缺少 `ownerUserId + kbId` filter。
- 禁止用未实现的空文件、空类、空接口伪装项目进度。
