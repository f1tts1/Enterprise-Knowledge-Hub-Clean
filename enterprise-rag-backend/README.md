# enterprise-rag-backend

Spring Boot 业务后端，负责 Enterprise Knowledge Hub 的用户、鉴权、知识库、文档、索引任务、权限边界和服务编排。

当前已实现：

- 用户注册、登录、JWT 鉴权、当前用户信息。
- 个人知识库创建、列表、详情、逻辑删除。
- 文档上传到 MinIO，文档元数据和索引任务写入 MySQL。
- 上传事务提交后通过 RabbitMQ 异步触发 Python 索引。
- RabbitMQ 消费者处理索引任务，并调用 FastAPI。
- 索引成功后更新 `document.index_status=INDEXED`、`indexing_task.status=SUCCESS`。
- 文档列表、详情、索引状态、逻辑删除，并同步调用 Python 清理 Qdrant vectors。
- 知识库内向量检索入口：`POST /api/v1/knowledge-bases/{kbId}/retrieval/search`。
- 检索前校验当前用户拥有知识库，并在返回前按 MySQL 未删除文档过滤命中结果。

当前未实现：

- RAG 问答、SSE、Agent、评测。
- 自动重试失败索引任务。
- Qdrant vectors 清理失败后的补偿任务或 outbox 重试机制。

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
