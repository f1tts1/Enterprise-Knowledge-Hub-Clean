from fastapi import FastAPI

from app.api.v1.documents import router as documents_router
from app.api.v1.rag import router as rag_router
from app.api.v1.retrieval import router as retrieval_router

# FastAPI 没有 Java 那种固定的启动类。这里的 app 对象就是应用入口。
# 启动命令 `uvicorn app.main:app` 的含义是：
#   1. 导入 app/main.py 这个模块
#   2. 找到下面这个名为 app 的 FastAPI 实例
#   3. 由 uvicorn 负责启动 HTTP 服务并把请求分发到已挂载的 router
app = FastAPI(title="RAG AI Service", version="0.0.1")

# Java 调用 http://localhost:8000/api/v1/documents/index 时，
# 请求会先进入这个 FastAPI app，再根据 include_router 挂载关系，
# 路由到 app/api/v1/documents.py 里的 index_document 函数。
app.include_router(documents_router)
app.include_router(retrieval_router)
app.include_router(rag_router)
