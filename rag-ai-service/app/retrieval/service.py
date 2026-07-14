from __future__ import annotations

from app.clients.qdrant_client import QdrantChunkVectorStore, QdrantSearchResult
from app.config.settings import get_settings
from app.embedding.factory import create_embedding_provider
from app.schemas.retrieval import (
    RetrievalSearchItem,
    RetrievalSearchRequest,
    RetrievalSearchResponse,
)


class RetrievalService:
    """知识库内向量检索服务。

    这一层只做 retrieval，不做 LLM 生成。这样可以先独立验证：
    1. query embedding 是否可用
    2. Qdrant 是否能按 ownerUserId + kbId 过滤
    3. 返回的 chunk 是否具备后续引用溯源需要的 metadata
    """

    def __init__(self) -> None:
        settings = get_settings()
        self._settings = settings
        self._embedding_provider = create_embedding_provider(settings)
        # Qdrant client 延迟创建，让单纯加载 FastAPI app 时不强依赖 Qdrant 已启动。
        self._vector_store: QdrantChunkVectorStore | None = None

    def search(self, request: RetrievalSearchRequest) -> RetrievalSearchResponse:
        query = request.query.strip()
        embedding_batch = self._embedding_provider.embed_texts([query])
        if embedding_batch.embedded_count != 1:
            raise RuntimeError("检索需要启用 embedding，并且 query 必须生成一个向量")

        results = self._get_vector_store().search_chunks(
            owner_user_id=request.owner_user_id,
            kb_id=request.kb_id,
            query_vector=embedding_batch.vectors[0],
            limit=request.top_k,
        )
        return RetrievalSearchResponse(
            query=query,
            top_k=request.top_k,
            vector_store="qdrant",
            vector_collection=self._get_vector_store().collection_name,
            records=[_to_item(result) for result in results],
        )

    def _get_vector_store(self) -> QdrantChunkVectorStore:
        if self._vector_store is None:
            self._vector_store = QdrantChunkVectorStore(self._settings)
        return self._vector_store


def _to_item(result: QdrantSearchResult) -> RetrievalSearchItem:
    payload = result.payload
    return RetrievalSearchItem(
        point_id=result.point_id,
        score=result.score,
        doc_id=_as_int(payload.get("docId")),
        chunk_id=_as_str(payload.get("chunkId")),
        file_name=_as_str(payload.get("fileName")),
        page_no=_as_int(payload.get("pageNo")),
        chunk_index=_as_int(payload.get("chunkIndex")),
        char_start=_as_int(payload.get("charStart")),
        char_end=_as_int(payload.get("charEnd")),
        text=_as_str(payload.get("text")),
    )


def _as_int(value: object) -> int | None:
    if value is None:
        return None
    return int(value)


def _as_str(value: object) -> str | None:
    if value is None:
        return None
    return str(value)


retrieval_service = RetrievalService()
