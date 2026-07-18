# Docker Compose 本机演示环境

## 定位

根目录 `compose.yaml` 用于在一台开发机上复现项目演示拓扑。它不是生产部署模板，不提供多副本、高可用、TLS、备份、告警、滚动发布或 Kubernetes 资源。

## 服务与数据

| 服务 | 职责 | 持久化 |
| --- | --- | --- |
| `mysql` | 业务事实、任务状态、模型调用日志 | `mysql-data` |
| `redis` | 会话、用户快照和知识库访问缓存 | `redis-data` |
| `rabbitmq` | 文档索引任务队列与 DLQ | `rabbitmq-data` |
| `minio` | 上传原文件 | `minio-data` |
| `qdrant` | chunk vectors 与检索 payload | `qdrant-data` |
| `model-init` | 首次下载本地 embedding 模型 | `embedding-model` |
| `rag-ai-service` | 解析、切分、embedding、Qdrant、生成 | 只读模型卷 |
| `enterprise-rag-backend` | 业务 API、权限、状态和控制台 | 无本地容器卷 |

所有宿主端口默认绑定 `127.0.0.1`。这点尤其重要：FastAPI 当前没有独立的服务间 token，安全边界仍依赖本机端口限制和 Java 作为公开业务入口。

## 启动

```bash
cp .env.example .env
# 替换密码、JWT secret；可选配置 LLM。
docker compose config --quiet
docker compose up --build
```

首次模型下载依赖 Hugging Face 网络和足够磁盘空间。可以通过 `.env` 的 `HF_ENDPOINT` 调整镜像站，但模型文件不得提交到 Git。

应用入口：

- 控制台：`http://localhost:${BACKEND_PORT:-8080}/console/`
- Java API：`http://localhost:${BACKEND_PORT:-8080}`
- FastAPI 本机调试：`http://localhost:${AI_SERVICE_PORT:-8000}`
- RabbitMQ management：`http://localhost:${RABBITMQ_MANAGEMENT_PORT:-15672}`
- MinIO console：`http://localhost:${MINIO_CONSOLE_PORT:-9001}`

项目不增加 `/health` 或 `/api/v1/health`。Compose 的依赖健康检查只用于基础中间件启动顺序，不应描述成应用业务就绪探针。

## 初始化与重置

MySQL 官方镜像只在空数据目录时执行 `docker-entrypoint-initdb.d` 中的 V1。V1 自身会删除并重建数据库，因此：

- 新命名卷：允许 V1 初始化。
- 已有 Compose 卷：不会再次自动执行 V1。
- 外部已有数据库：不能指望 Compose 自动迁移；应备份后按缺失版本执行 V2/V3。

停止并保留数据：

```bash
docker compose down
```

删除所有演示数据和模型缓存：

```bash
docker compose down -v
```

第二条命令具有破坏性，只能在明确接受本地数据和模型重新下载时执行。

## LLM 配置

未配置 `LLM_*` 或 `DEEPSEEK_API_KEY` 时：

- 文档上传、异步索引和向量检索仍可工作。
- 空知识库 RAG 仍由 Java 短路返回 `NO_CONTEXT`。
- 有候选上下文且需要生成时，FastAPI 返回 LLM 未配置错误，Java 对外映射为 AI 服务不可用。

LLM Key 仅写入本机 `.env` 或进程环境，不写 Dockerfile、Compose 默认值、日志或提交历史。

## 当前验证证据

本阶段已完成 Compose 静态解析；没有在本轮拉取镜像、实际构建镜像、启动容器或执行 Compose 端到端回归。因此当前可以表述为“具备本机 Compose 演示配置并通过静态解析”，不能表述为“已验证一键部署”或“生产可用”。

首次实际演示应额外记录：Docker/Compose 版本、CPU 架构、模型下载耗时、镜像构建结果、8 个服务状态、上传索引检索路径、可选 RAG 路径和关闭/重启后的数据保留情况。

## 明确不扩展

- 不添加 Kubernetes、Helm、服务网格或注册中心。
- 不为演示引入 Kafka、Elasticsearch或新的 secret manager。
- 不伪造生产 TLS、高可用、备份和告警能力。
- 不为了 Compose 增加匿名 health API 或 test-only 业务接口。

