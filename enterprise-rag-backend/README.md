# enterprise-rag-backend

Spring Boot 业务后端，负责 Enterprise Knowledge Hub 的用户、鉴权、知识库、文档、索引任务、权限边界和服务编排。

当前已实现：

- 用户注册、登录、JWT 鉴权、当前用户信息。
- 个人知识库创建、列表、详情、逻辑删除。
- 文档上传到 MinIO，文档元数据和索引任务写入 MySQL。
- 上传事务提交后通过 RabbitMQ 异步触发 Python 索引。
- RabbitMQ 消费者处理索引任务，并调用 FastAPI。
- 失败重试创建不可变 task attempt，`document.current_indexing_task_id` 阻止旧 worker 覆盖当前状态；PENDING 传输恢复只重投同一 attempt。
- 索引成功后更新 `document.index_status=INDEXED`、`indexing_task.status=SUCCESS`。
- 文档列表、详情、索引状态、逻辑删除，并用 `delete_generation` 约束迟到删除结果，再同步调用 Python 清理 Qdrant vectors。
- 知识库内向量检索入口：`POST /api/v1/knowledge-bases/{kbId}/retrieval/search`。
- 检索前校验当前用户拥有知识库，并在返回前按 MySQL 未删除文档过滤命中结果。
- 最小同步 RAG 问答：Java 复用权限过滤后的检索结果，Python 调用 OpenAI-compatible LLM 生成答案；公开响应使用 `ANSWERED`、`NO_CONTEXT`、`INSUFFICIENT_CONTEXT`，并区分候选 `retrievedChunks` 与答案实际采用的 `citations`。
- 入口 requestId 安全归一化并传播到 FastAPI；RabbitMQ 索引 attempt 使用稳定的 `index-task-{indexingTaskId}` 关联日志。
- 索引、检索、RAG 和 Java→Python HTTP 调用记录低基数 Micrometer 指标；`model_call_log` 以 best-effort 方式记录 embedding/CHAT 调用耗时、结果和可用 token usage。
- Actuator 只暴露 Prometheus 抓取端点，并继续受 JWT 鉴权保护。

当前未实现：

- SSE、Agent、多轮会话落库。
- FAILED 索引 attempt 的无人值守自动业务重试；当前只自动恢复 PENDING 发布失败，FAILED 通过接口人工创建新 attempt。
- Qdrant vectors 清理失败后的补偿任务或 outbox 重试机制。
- Grafana 看板、告警、长期指标存储、LLM TTFT 和正式 RAG 评测平台。

## Core Verification

从仓库根目录执行：

```bash
./scripts/verify.sh
docker compose --env-file .env.example config --quiet
```

第一条运行 Java/Python 确定性测试与契约检查；第二条只静态解析 Compose。它们不启动中间件、应用或真实 LLM，也不证明镜像构建和端到端链路已通过。证据分层见 `../docs/TESTING.md`。

根目录已提供本机演示用 `compose.yaml` 和 Dockerfile，使用边界见 `../docs/DEPLOYMENT.md`。当前不能把这套配置描述成已验证的一键部署或生产部署方案。

## Local Run

默认依赖：

- MySQL：`localhost:3306`
- Redis：`localhost:6379`，凭证通过本地配置提供。
- RabbitMQ：`localhost:5672`，凭证通过本地配置提供。
- MinIO：`http://localhost:9000`，凭证通过本地配置提供。
- FastAPI：`http://localhost:8000`
- Qdrant：由 Python 服务访问，默认 `http://localhost:6333`

初始化数据库：

```bash
mysql -u root -p < src/main/resources/db/migration/V1__init_schema.sql
```

全新或可重置环境只执行 V1，V1 已包含当前最终结构，不能随后再跑 V3。已有旧 V1/V2 数据库才在停应用、备份后依次执行缺失迁移；V3 是一次性非幂等 DDL，失败后应恢复备份再处理：

```bash
mysql -u root -p < src/main/resources/db/migration/V2__fix_document_active_checksum_index.sql
mysql -u root -p < src/main/resources/db/migration/V3__add_indexing_attempt_and_deletion_fencing.sql
```

V3 在 DDL 前会拒绝“同一 document 存在多条旧 task”的异常数据，避免猜测 current task。

启动：

```bash
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
# 编辑 application-local.yml，替换本地凭证占位值；该文件不会被 Git 跟踪。
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

编译：

```bash
mvn -q -DskipTests -o compile
```

查看 Prometheus 文本指标需要先登录取得 access token：

```bash
curl http://localhost:8080/actuator/prometheus \
  -H "Authorization: Bearer {accessToken}"
```

`/actuator/prometheus` 没有匿名放行；当前仍没有 `/health` 或 `/api/v1/health`。指标只使用有限枚举标签，不把 requestId、用户或业务实体 ID 放入 tag。完整字段和排查方式见 `../docs/OBSERVABILITY.md`。

## 真实业务入口

注册：

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"Password123","nickname":"Alice","email":"alice@example.com"}'
```

登录：

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"Password123"}'
```

上传文档：

```bash
curl -X POST "http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents" \
  -H "Authorization: Bearer {token}" \
  -F "file=@/absolute/path/to/document.txt"
```

检索知识库：

```bash
curl -X POST "http://localhost:8080/api/v1/knowledge-bases/{kbId}/retrieval/search" \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"query":"RabbitMQ 异步索引","topK":5}'
```

当前没有 `/health` 或 `/api/v1/health` 接口，连通性通过真实业务接口验证。

RAG 问答：

```bash
curl -X POST "http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag/ask" \
  -H "Authorization: Bearer {token}" \
  -H 'Content-Type: application/json' \
  -d '{"question":"RabbitMQ 异步索引如何恢复？","topK":5}'
```

响应语义：

- `ANSWERED`：`noAnswer=false`，正文至少有一个有效 `[片段 n]`，`citations` 只映射这些实际编号。
- `NO_CONTEXT`：Java 没有检索到可用上下文并短路，`noAnswer=true`、`citations=[]`，不调用 LLM。
- `INSUFFICIENT_CONTEXT`：模型返回受控 no-answer，或启用的相关性阈值过滤掉全部上下文；`noAnswer=true`、`citations=[]`。

`app.rag.minimum-relevance-score` / `RAG_MINIMUM_RELEVANCE_SCORE` 默认 `-1`（关闭）。历史固定集的可回答题和无答案题分数有重叠，未基于当前数据重新校准前不要随意打开全局阈值。缺失引用、越界引用或 no-answer sentinel 与其它文本混用会被映射为 AI 服务失败，而不是伪装成成功回答。
