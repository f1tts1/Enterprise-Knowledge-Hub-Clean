from fastapi import APIRouter, HTTPException

from app.clients.qdrant_client import VectorStoreError
from app.retrieval.service import retrieval_service
from app.schemas.retrieval import RetrievalSearchRequest, RetrievalSearchResponse

router = APIRouter(prefix="/api/v1/retrieval", tags=["retrieval"])


@router.post("/search", response_model=RetrievalSearchResponse)
async def search(request: RetrievalSearchRequest) -> RetrievalSearchResponse:
    """Java 后端调用的知识库内向量检索入口。

    这是 RAG 问答前的独立验证接口：只返回 Qdrant 命中的 chunk，
    不调用 LLM，也不生成最终答案。
    """
    try:
        return retrieval_service.search(request)
    except VectorStoreError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
