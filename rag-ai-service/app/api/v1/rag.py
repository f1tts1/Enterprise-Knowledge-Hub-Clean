from fastapi import APIRouter, HTTPException

from app.generator.service import LlmProviderError, rag_generation_service
from app.schemas.rag import RagGenerateRequest, RagGenerateResponse

router = APIRouter(prefix="/api/v1/rag", tags=["rag"])


@router.post("/generate", response_model=RagGenerateResponse)
async def generate_answer(request: RagGenerateRequest) -> RagGenerateResponse:
    """Java 已完成检索和权限过滤后调用的答案生成入口。"""
    try:
        return await rag_generation_service.generate(request)
    except LlmProviderError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
