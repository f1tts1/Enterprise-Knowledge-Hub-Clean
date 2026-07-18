# Enterprise Knowledge Hub

Enterprise Knowledge Hub 是一个面向 Java 后端与 AI 工程化求职展示的企业知识库项目。它不是 LangChain Demo：Spring Boot 负责业务事实、权限和任务状态，FastAPI 负责文档处理、embedding、Qdrant 与受控生成。

## 当前主链路

```text
上传文档
  -> Java 校验知识库 owner，写 MinIO / MySQL
  -> RabbitMQ 投递不可变 indexing attempt
  -> Python 下载、解析、切分、embedding、写 Qdrant
  -> Java 通过 current task fencing 更新索引状态
  -> Java 权限校验 + Python Qdrant metadata filter + Java 二次过滤
  -> Java 编排同步 RAG，Python 只消费已过滤上下文
  -> 返回结构化回答状态、实际引用与候选检索片段
  -> 删除时按 generation fencing 清理 Qdrant / MinIO
```

当前 RAG 返回三种业务状态：

- `ANSWERED`：答案至少包含一个合法 `[片段 n]`，`citations` 只返回答案实际引用的候选片段。
- `NO_CONTEXT`：没有检索结果或结果正文不可用，Java 短路且不调用 LLM。
- `INSUFFICIENT_CONTEXT`：可选阈值过滤后无上下文，或模型按受控协议拒答。

`retrievedChunks` 是本次权限过滤后的候选检索结果，不等同于答案引用。历史 baseline 显示可回答题与无答案题的向量分数存在重叠，因此 `RAG_MINIMUM_RELEVANCE_SCORE` 默认 `-1`（关闭），不能把未经实验的统一阈值当作可靠拒答策略。

## 工程能力

- Java 17 + Spring Boot 3.3.5、Spring Security、JWT、MyBatis-Plus。
- MySQL 保存用户、知识库、文档、不可变索引 attempt 与删除 generation。
- RabbitMQ 提供上传后的异步索引边界、发布重投、消费重试和 DLQ。
- Redis 保存认证用户快照、Refresh Token 会话与知识库访问缓存。
- MinIO 保存原文件，Qdrant 保存带 `ownerUserId + kbId` 的 chunk vectors。
- FastAPI 解析 TXT/Markdown/PDF/DOCX，使用本地 `BAAI/bge-small-zh-v1.5` embedding。
- `X-Request-Id` 关联 Java、FastAPI 与 LLM；异步任务使用稳定的 `index-task-{taskId}`。
- Actuator/Micrometer 提供低基数索引、检索、RAG 和模型调用指标；`model_call_log` 旁路落库。
- 单元测试、跨语言 DTO fixture、固定评测资产、权限/E2E 脚本及可靠性演练脚本分层管理。
- Docker Compose 提供本机可复现演示拓扑，但不是生产部署方案。

## 目录

- `enterprise-rag-backend/`：Spring Boot 业务后端与同源控制台。
- `rag-ai-service/`：FastAPI AI 服务。
- `contracts/ai/`：Java/Python 共用的内部 DTO 契约样例。
- `eval/rag/`：10 份固定语料、50 条问题和本地生成结果目录。
- `scripts/`：统一验证、模型下载、权限/RAG 回归和可靠性演练。
- `docs/`：架构、状态、决策、API、测试、部署和证据说明。

## Docker Compose 本机演示

从仓库根目录执行：

```bash
cp .env.example .env
# 编辑 .env：替换所有密码/JWT 占位值；需要生成答案时再配置 LLM。
docker compose config --quiet
docker compose up --build
```

首次启动时 `model-init` 会从 Hugging Face 下载 embedding 模型到命名卷，耗时取决于网络和磁盘。默认宿主端口只绑定 `127.0.0.1`；控制台地址是 `http://localhost:8080/console/`，RabbitMQ 与 MinIO 管理页面分别使用 `.env` 中的 management/console 端口。

不配置 LLM 时，上传、索引和向量检索仍可使用；存在检索上下文的 RAG 生成会返回服务不可用。停止容器但保留数据：

```bash
docker compose down
```

`docker compose down -v` 会删除 MySQL、Redis、RabbitMQ、MinIO、Qdrant 和 embedding 模型卷，只能在确认要重置本地演示数据时执行。V1 SQL 含删除数据库语句，Compose 仅在新的空 MySQL 卷初始化它；已有数据库迁移不能靠重新挂载 V1 完成。详见 `docs/DEPLOYMENT.md`。

## 非容器本地运行

以下命令都从仓库根目录执行。

全新或可重置数据库只执行 V1：

```bash
mysql -u root -p < enterprise-rag-backend/src/main/resources/db/migration/V1__init_schema.sql
```

V1 会删除并重建 `enterprise_knowledge_hub`。已有旧库应先停止应用并备份，再按实际缺失版本一次性执行 V2/V3；V3 不是幂等迁移，失败后应恢复备份排查，不能直接重跑。

准备 Python：

```bash
conda activate rag-ai-service
python -m pip install -r rag-ai-service/requirements.txt
python scripts/download_embedding_model.py
cp rag-ai-service/.env.example rag-ai-service/.env
# 编辑 rag-ai-service/.env
cd rag-ai-service
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

另开终端启动 Java：

```bash
cd enterprise-rag-backend
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
# 编辑 application-local.yml
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

LLM 使用 OpenAI-compatible 配置：

```bash
export LLM_PROVIDER=openai-compatible
export LLM_BASE_URL="https://your-provider.example/v1"
export LLM_API_KEY="set-in-local-shell-only"
export LLM_MODEL="your-chat-model"
```

也支持本地环境中的 `DEEPSEEK_API_KEY` 回退。任何 API Key、JWT secret 或中间件密码都不得提交到 Git。

## 验证入口与证据边界

安装轻量 Python 测试依赖后，从仓库根目录执行确定性验证：

```bash
python -m pip install -r rag-ai-service/requirements-test.txt
./scripts/verify.sh
docker compose --env-file .env.example config --quiet
```

`verify.sh` 执行所有 shell 语法检查、Java 单元测试和 Python 核心单元/契约测试，不启动服务，也不下载模型。GitHub Actions 在 PR、main push 和手动触发时执行同一入口并静态解析 Compose。

已启动完整本地环境后，才可运行：

```bash
./scripts/test_retrieval_permission.sh
./scripts/test_document_reupload_delete.sh
./scripts/test_rag_ask.sh
./scripts/test_rag_empty_kb.sh
./scripts/test_rag_permission.sh
python3 scripts/run_rag_eval.py --retrieval-only
```

RAG 生成脚本还要求 FastAPI 进程已配置 LLM。固定集属于轻量回归基线，不是正式评测平台；历史结果不能替代当前提交的重新执行证据。

RabbitMQ、Qdrant 和 MinIO 的交互式故障演练见 `docs/RELIABILITY_MATRIX.md`。仓库严格区分：

- 自动测试已经通过。
- 演练脚本已经就绪。
- 外部依赖演练实际执行并留证。
- 尚未验证的设计或环境假设。

## 当前明确不做

- SSE、多轮会话与引用落库。
- Agent 工作流。
- hybrid search、rerank、query rewrite。
- 正式 RAG 评测/实验平台。
- Grafana 看板、告警和长期指标存储。
- Kubernetes、服务网格、微服务注册中心或额外搜索中间件。

项目不提供 `/health` 或 `/api/v1/health`。`/actuator/prometheus` 仍受 JWT 保护，完整非流式 LLM 耗时不能描述成首 token 延迟。

## 文档索引

- `AGENTS.md`：仓库开发约束。
- `docs/ARCHITECTURE.md`：当前系统架构。
- `docs/STATUS.md`：完成项、证据等级和已知限制。
- `docs/DECISIONS.md`：架构决策及取舍。
- `docs/API_CONTRACT.md`：Java/Python API 契约。
- `docs/TESTING.md`：验证层级与命令。
- `docs/DEPLOYMENT.md`：Compose 演示环境与风险边界。
- `docs/OBSERVABILITY.md`：关联 ID、耗时、模型调用日志和指标。
- `docs/RAG_EVALUATION.md`：轻量 RAG 回归基线。
- `docs/RELIABILITY_MATRIX.md`：异步索引、删除与权限可靠性矩阵。
