# rag-ai-service

FastAPI AI 服务，负责 Enterprise Knowledge Hub 的文档解析、文本切分、本地 embedding、Qdrant 写入和知识库内向量检索。

当前已实现：

- `POST /api/v1/documents/index`
  - 从 MinIO 下载文件。
  - 解析 TXT、Markdown、PDF、DOCX。
  - 切分 chunk。
  - 使用本地 `BAAI/bge-small-zh-v1.5` 生成 embedding。
  - 写入 Qdrant。
- `POST /api/v1/documents/delete-vectors`
  - 按 Java 已校验后传入的 `ownerUserId + kbId + docId` 删除 Qdrant points。
  - 不访问 MySQL，不删除 MinIO object。
- `POST /api/v1/retrieval/search`
  - 对 query 生成 embedding。
  - 使用 `ownerUserId + kbId` 过滤 Qdrant。
  - 返回命中的 chunk、score 和引用元数据。
- `POST /api/v1/rag/generate`
  - 只消费 Java 已完成权限过滤的 chunk context。
  - 调用 OpenAI-compatible Chat Completions，并返回完整调用耗时和 provider 提供的 token usage。
  - 可回答时解析、去重并校验答案正文中的 `[片段 n]`，返回 `answer_status=ANSWERED` 和实际 `cited_context_indexes`。
  - 上下文不足时只接受完整的内部 sentinel，转换为 `answer_status=INSUFFICIENT_CONTEXT`、固定安全回答、空引用和原因；sentinel 混用、缺失引用或越界引用均按协议失败。
- 请求关联与阶段耗时
  - FastAPI 中间件校验或生成 `X-Request-Id`，写回响应头，并通过 `ContextVar` 隔离并发请求上下文。
  - 索引响应返回下载、解析、切分、embedding、Qdrant 写入及总耗时。
  - 检索响应返回 query embedding、Qdrant 检索及总耗时。
  - 调用外部 LLM 时继续传递同一个 `X-Request-Id`；业务日志不记录问题、chunk、prompt、答案或凭证正文。

当前不做完整登录鉴权、MySQL 状态维护或 SSE 流式输出。Python 内部接口仍依赖部署网络边界，尚未实现 Java→FastAPI 服务间 token/mTLS；因此不能把 FastAPI 直接暴露到不受信任网络。完整用户权限、任务状态和 `model_call_log` 持久化仍由 Spring Boot 负责。

`llm_latency_ms` 是当前非流式 HTTP 调用的完整耗时，不是 TTFT。服务目前没有 Prometheus 端点、Grafana 看板、正式 RAG 评测系统或长期指标存储；Java 会汇总 Python 返回的阶段字段并暴露基础 Micrometer 指标。

Python 内部响应只有 `ANSWERED` 与 `INSUFFICIENT_CONTEXT`。公开 API 的 `NO_CONTEXT` 由 Java 在没有检索候选或没有可用文本时短路生成；Python 不负责完整业务权限，也不能自行访问任意知识库。`cited_context_indexes` 表示模型答案实际引用的上下文位置，不等于 Java 检索返回的全部候选。

## Core Verification

从仓库根目录执行：

```bash
python -m pip install -r rag-ai-service/requirements-test.txt
./scripts/verify.sh
docker compose --env-file .env.example config --quiet
```

统一入口包含 loader、splitter、Qdrant filter/payload、生成协议、固定评测资产和 Java/Python DTO fixture 的确定性检查。它不加载真实 embedding 模型、不调用真实 LLM，也不启动 Compose。最后一条只静态解析配置，不能证明镜像已构建或服务已启动。详见 `../docs/TESTING.md` 与 `../docs/DEPLOYMENT.md`。

## Local Embedding Model

默认模型目录：

```text
.models/bge-small-zh-v1.5
```

从仓库根目录下载：

```bash
conda activate rag-ai-service
python scripts/download_embedding_model.py
```

如 HuggingFace 直连较慢：

```bash
HF_ENDPOINT=https://hf-mirror.com python scripts/download_embedding_model.py
```

## Local Run

```bash
cd rag-ai-service
conda activate rag-ai-service
cp .env.example .env
# 编辑 .env，替换 MinIO 凭证占位值；.env 不会被 Git 跟踪。
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

默认依赖：

- MinIO：`localhost:9000`，凭证从本地 `.env` 读取。
- Qdrant：`http://localhost:6333`

回到仓库根目录后执行 Python 语法检查：

```bash
python3 -m compileall rag-ai-service/app scripts/download_embedding_model.py
```
