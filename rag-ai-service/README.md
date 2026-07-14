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

当前不做完整登录鉴权、MySQL 状态维护、LLM 生成或 SSE 流式输出。这些边界由 Spring Boot 或后续 RAG 模块负责。

## Local Embedding Model

默认模型目录：

```text
.models/bge-small-zh-v1.5
```

从仓库根目录下载：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
conda activate rag-ai-service
python scripts/download_embedding_model.py
```

如 HuggingFace 直连较慢：

```bash
HF_ENDPOINT=https://hf-mirror.com python scripts/download_embedding_model.py
```

## Local Run

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub/rag-ai-service"
conda activate rag-ai-service
cp .env.example .env
# 编辑 .env，替换 MinIO 凭证占位值；.env 不会被 Git 跟踪。
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

默认依赖：

- MinIO：`localhost:9000`，凭证从本地 `.env` 读取。
- Qdrant：`http://localhost:6333`

Python 语法检查：

```bash
cd "/Users/fitts/codeProjects/Projects/Enterprise Knowledge Hub"
python3 -m compileall rag-ai-service/app scripts/download_embedding_model.py
```
