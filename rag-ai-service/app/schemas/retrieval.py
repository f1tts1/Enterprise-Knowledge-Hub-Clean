from __future__ import annotations

from pydantic import BaseModel, Field


class RetrievalSearchRequest(BaseModel):
    """Java 调用 Python 检索接口时传入的最小 DTO。

    用户权限不在 Python 登录态里判断，但 Java 会传入已经校验过的 owner_user_id
    和 kb_id。Python 必须把这两个字段下推到 Qdrant filter。
    """

    owner_user_id: int = Field(gt=0)
    kb_id: int = Field(gt=0)
    query: str = Field(min_length=1, max_length=512)
    top_k: int = Field(default=5, ge=1, le=20)


class RetrievalSearchItem(BaseModel):
    """返回给 Java 的单条检索命中。

    text 是后续 RAG prompt 的候选上下文；doc/page/chunk/offset 字段用于引用溯源。
    """

    point_id: str
    score: float
    doc_id: int | None = None
    chunk_id: str | None = None
    file_name: str | None = None
    page_no: int | None = None
    chunk_index: int | None = None
    char_start: int | None = None
    char_end: int | None = None
    text: str | None = None


class RetrievalSearchResponse(BaseModel):
    query: str
    top_k: int
    vector_store: str
    vector_collection: str
    records: list[RetrievalSearchItem]
