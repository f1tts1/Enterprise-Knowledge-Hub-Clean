# Enterprise Knowledge Hub

Enterprise Knowledge Hub 是一个面向 Java 后端 / AI 工程化求职展示的企业知识库项目。

当前已经落地的主链路：

```text
Spring Boot 上传文档
  -> MinIO 保存原文件
  -> MySQL 保存 document / 不可变 indexing_task attempt
  -> RabbitMQ 异步触发索引
  -> FastAPI 从 MinIO 下载文件
  -> 解析 TXT/Markdown/PDF/DOCX
  -> 切分 chunk
  -> 本地 BAAI/bge-small-zh-v1.5 生成 embedding
  -> 写入 Qdrant
  -> Java 更新索引状态
  -> Java 检索接口按用户和知识库权限返回 Qdrant chunk
  -> Java RAG 问答接口复用检索 chunk 并调用 FastAPI LLM 生成
  -> 删除文档时同步清理对应 Qdrant vectors
```

当前已提供最小同步 RAG 问答接口：基于已索引 chunk 生成答案并返回引用片段。还没有 SSE 流式输出、会话引用落库、复杂 no-answer 判断、正式评测系统或 Agent 工作流。

当前也已落地 P0-2 基础可观测性：同步请求的 `X-Request-Id` 会从 Java 传播到 FastAPI 和外部 LLM；异步索引 attempt 使用稳定的 `index-task-{indexingTaskId}`；Python 返回索引、检索和 LLM 阶段耗时与 provider usage；Java 记录低基数 Micrometer 指标，并以 best-effort 方式写入 `model_call_log`。这些能力用于定位一次调用慢或失败在哪一段，不代表已经具备 Grafana 看板、告警、长期指标存储或正式 RAG 评测平台。

P0-3 将“可靠性设计”收敛为可复现证据：Java 测试覆盖迟到 worker、timeout、Qdrant/MinIO 删除失败和 generation fencing；交互式脚本覆盖 RabbitMQ 停机恢复、重复消息、DLQ，以及 Qdrant/MinIO 删除失败恢复。仓库严格区分“自动测试通过、脚本已就绪、外部演练通过”，不会把脚本存在描述成真实依赖故障已经验证。

## 目录

- `enterprise-rag-backend`：Spring Boot 业务后端。
- `rag-ai-service`：FastAPI AI 服务。
- `docs`：当前架构、状态、决策和 API 契约。
- `scripts`：模型下载、固定测试 fixture、权限/RAG 回归和可靠性故障演练脚本。

## 本地运行

先启动本地依赖：

- MySQL：`localhost:3306`
- Redis：`localhost:6379`，凭证通过本地配置提供。
- RabbitMQ：`localhost:5672`，凭证通过本地配置提供。
- MinIO：`http://localhost:9000`，凭证通过本地配置提供。
- Qdrant：`http://localhost:6333`

初始化 MySQL：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/enterprise-rag-backend"
mysql -u root -p < src/main/resources/db/migration/V1__init_schema.sql
```

注意：当前 SQL 会先删除 `enterprise_knowledge_hub` 数据库，只适合本地重置测试环境。

数据库初始化有两条互斥路径：全新或可重置环境只执行 V1（V1 已包含当前最终结构），不要随后再执行 V2/V3。只有已有旧 V1/V2 数据库才执行缺失迁移；执行前停止应用并备份，V3 是一次性、非幂等 DDL，失败时应恢复备份后排查，不能直接重复执行：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/enterprise-rag-backend"
mysql -u root -p < src/main/resources/db/migration/V2__fix_document_active_checksum_index.sql
mysql -u root -p < src/main/resources/db/migration/V3__add_indexing_attempt_and_deletion_fencing.sql
```

V3 会在任何 DDL 前检查“每个文档至多一条旧 indexing task”这一旧代码不变量；发现异常多 task 数据会主动终止，必须先人工确认 current task 和历史关系。

下载本地 embedding 模型：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
conda activate rag-ai-service
python scripts/download_embedding_model.py
```

启动 FastAPI：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/rag-ai-service"
conda activate rag-ai-service
cp .env.example .env
# 编辑 .env，替换 MinIO 凭证占位值；.env 不会被 Git 跟踪。
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

如需使用 RAG 问答，FastAPI 会优先读取通用 OpenAI-compatible 配置：

```bash
export LLM_PROVIDER=openai-compatible
export LLM_BASE_URL="https://your-openai-compatible-host/v1"
export LLM_API_KEY="your-api-key"
export LLM_MODEL="your-chat-model"
```

如果本机已经设置 `DEEPSEEK_API_KEY`，也可以不手动设置上面的 `LLM_*` 变量；Python 会自动使用 DeepSeek 默认配置：

```text
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-chat
```

启动 Spring Boot：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/enterprise-rag-backend"
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
# 编辑 application-local.yml，替换本地凭证占位值；该文件不会被 Git 跟踪。
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

打开前端应用：

```text
http://localhost:8080/console/
```

前端由 Spring Boot 同源提供，使用浏览器端路由组织登录页、注册页、登录后主布局、知识库工作台、文档与索引、向量检索和同步 RAG 问答页面。前端只调用 Java API；同步 RAG 问答通过 Java `/api/v1/knowledge-bases/{kbId}/rag/ask` 调用，前端不接触任何 LLM API Key，也不提供 SSE、历史会话或 Agent 页面。

查看 Prometheus 文本指标：

```bash
curl http://localhost:8080/actuator/prometheus \
  -H "Authorization: Bearer {accessToken}"
```

该端点没有匿名放行，仍受现有 JWT 安全链保护。项目没有恢复 `/health` 或 `/api/v1/health`；详细的关联规则、指标名、`model_call_log` 语义和排查查询见 `docs/OBSERVABILITY.md`。

## 当前测试入口

权限检索端到端测试：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
./scripts/test_retrieval_permission.sh
```

脚本会创建 Alice/Bob 两个用户和知识库，上传不同 TXT 文档，等待索引进入 `INDEXED/SUCCESS`，验证不同用户不能检索到对方知识库内容，并验证删除文档后不会再召回该文档 chunk。

同内容文档重复上传删除回归测试：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
./scripts/test_document_reupload_delete.sh
```

该脚本验证同一文件“上传 -> 删除 -> 再上传 -> 再删除”不会与历史 deleted 文档行的 checksum 唯一约束冲突。已有本地库运行前需先执行 `V2__fix_document_active_checksum_index.sql`。

最小 RAG 验证脚本：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
./scripts/test_rag_ask.sh
./scripts/test_rag_empty_kb.sh
./scripts/test_rag_permission.sh
```

RAG 生成相关脚本需要 FastAPI 进程能读取 `DEEPSEEK_API_KEY` 或显式 `LLM_*` 配置。`test_rag_permission.sh` 会验证 Alice/Bob 不能通过 RAG 问答访问对方知识库，也不会在 chunk/citation 中混入对方文档。

最小 RAG 评测基线：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
python3 scripts/run_rag_eval.py --retrieval-only
```

如果 FastAPI 已配置可用 LLM，也可以去掉 `--retrieval-only` 运行完整 RAG 评测。评测会使用 `eval/rag/questions.jsonl` 中的固定问题集，输出 Recall@K、MRR、引用正确性、无答案识别和权限泄露检查结果。详细说明见 `docs/RAG_EVALUATION.md`。

该固定集和脚本属于轻量回归基线，不是带实验管理、在线追踪、人工/LLM judge 流程的正式 RAG 评测系统；P0-2 不扩展这部分能力。

可靠性验证矩阵：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
cd enterprise-rag-backend && mvn -q -o test && cd ..
./scripts/test_index_retry.sh
./scripts/test_knowledge_base_delete_guard.sh
./scripts/test_document_reupload_delete.sh
```

`test_index_retry.sh` 需要按提示临时停止并恢复 FastAPI。P0-3 的外部依赖演练单独执行：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"

# RabbitMQ management API 默认使用 localhost:15672。
# 交互读取密码，避免把明文写入 shell history。
printf 'RabbitMQ username: '
IFS= read -r RABBITMQ_USERNAME
printf 'RabbitMQ password: '
IFS= read -r -s RABBITMQ_PASSWORD
printf '\n'
export RABBITMQ_USERNAME RABBITMQ_PASSWORD
MYSQL_DEFAULTS_FILE="$HOME/.my-ekb.cnf" ./scripts/test_rabbitmq_reliability.sh

# Qdrant 与 MinIO 两种失败顺序需要分别演练。
MYSQL_DEFAULTS_FILE="$HOME/.my-ekb.cnf" \
./scripts/test_document_delete_failure_recovery.sh qdrant

MYSQL_DEFAULTS_FILE="$HOME/.my-ekb.cnf" \
./scripts/test_document_delete_failure_recovery.sh minio
```

`MYSQL_DEFAULTS_FILE` 应是本机权限受限的 MySQL client 配置文件，不要提交仓库。RabbitMQ 凭证会写入本次演练的权限受限临时 curl 配置，并在脚本退出时清理，不会出现在 curl 参数中。故障脚本不会猜测容器名或自动停服务，会提示操作者分阶段停止/恢复对应本地依赖；脚本只读取固定业务状态，不执行修复 SQL。

如果演练中途断言失败，先按脚本警告恢复被停止的依赖，再排查业务状态。RabbitMQ 脚本为验证 DLQ 会保留一条非法消息，不会自动 purge 可能属于其他排障任务的死信。

`scripts/test_retrieval_permission.sh` 和 `scripts/test_rag_permission.sh` 使用仓库内 `scripts/fixtures/retrieval-permission`，新 clone 不再依赖本地 `/tmp` 文件。完整场景、当前证据等级和尚未执行的外部演练见 `docs/RELIABILITY_MATRIX.md`。

## 重要文档

- `AGENTS.md`：仓库开发约束。
- `docs/ARCHITECTURE.md`：当前系统架构。
- `docs/STATUS.md`：已完成、未完成、已知问题。
- `docs/DECISIONS.md`：已确认技术决策。
- `docs/API_CONTRACT.md`：Java/Python API 契约。
- `docs/OBSERVABILITY.md`：requestId、阶段耗时、模型调用日志和低基数指标说明。
- `docs/RAG_EVALUATION.md`：最小 RAG 评测基线。
- `docs/RAG_EVALUATION_BASELINE.md`：当前 retrieval-only baseline 结果。
- `docs/RELIABILITY_MATRIX.md`：异步索引、删除一致性和权限隔离可靠性矩阵。
