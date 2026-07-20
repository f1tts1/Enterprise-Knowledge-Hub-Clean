from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class RagContextChunk(BaseModel):
    """Java 已完成权限过滤后传给 LLM 的上下文 chunk。"""

    doc_id: int | None = None
    chunk_id: str | None = None
    file_name: str | None = None
    page_no: int | None = None
    chunk_index: int | None = None
    score: float | None = None
    text: str = Field(min_length=1, max_length=4000)


class RagGenerateRequest(BaseModel):
    """Java 调用 Python 生成答案时使用的内部 DTO。"""

    question: str = Field(min_length=1, max_length=512)
    contexts: list[RagContextChunk] = Field(min_length=1, max_length=20)


class RagGenerateResponse(BaseModel):
    answer: str
    answer_status: Literal["ANSWERED", "INSUFFICIENT_CONTEXT"]
    cited_context_indexes: list[int] = Field(default_factory=list)
    no_answer_reason: str | None = None
    llm_provider: str
    llm_model: str
    # 当前是非流式 Chat Completions，只记录完整调用耗时，不能描述为 TTFT。
    llm_latency_ms: int = Field(ge=0)
    prompt_tokens: int | None = Field(default=None, ge=0)
    completion_tokens: int | None = Field(default=None, ge=0)
    total_tokens: int | None = Field(default=None, ge=0)
