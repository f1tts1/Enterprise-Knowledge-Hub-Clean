from __future__ import annotations

import logging
from time import perf_counter

from app.clients.qdrant_client import QdrantChunkVectorStore, QdrantSearchResult
from app.config.settings import get_settings
from app.embedding.factory import create_embedding_provider
from app.observability.request_context import elapsed_ms, request_id_for_log
from app.schemas.retrieval import (
    RetrievalSearchItem,
    RetrievalSearchRequest,
    RetrievalSearchResponse,
)


logger = logging.getLogger(__name__)


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
        total_started_at = perf_counter()
        query = request.query.strip()

        embedding_started_at = perf_counter()
        try:
            embedding_batch = self._embedding_provider.embed_texts([query])
            if embedding_batch.embedded_count != 1:
                raise RuntimeError("检索需要启用 embedding，并且 query 必须生成一个向量")
        except Exception as exc:
            _log_failure(request, "embedding", total_started_at, exc)
            raise
        embedding_latency_ms = elapsed_ms(embedding_started_at)

        vector_store_started_at = perf_counter()
        try:
            vector_store = self._get_vector_store()
            results = vector_store.search_chunks(
                owner_user_id=request.owner_user_id,
                kb_id=request.kb_id,
                query_vector=embedding_batch.vectors[0],
                limit=request.top_k,
            )
            records = [_to_item(result) for result in results]
            vector_collection = vector_store.collection_name
        except Exception as exc:
            _log_failure(request, "vector_store", total_started_at, exc)
            raise
        vector_store_latency_ms = elapsed_ms(vector_store_started_at)
        total_latency_ms = elapsed_ms(total_started_at)

        logger.info(
            "request_id=%s operation=retrieval_search owner_user_id=%s kb_id=%s "
            "top_k=%s hit_count=%s provider=%s model=%s embedding_latency_ms=%s "
            "vector_store_latency_ms=%s total_latency_ms=%s",
            request_id_for_log(),
            request.owner_user_id,
            request.kb_id,
            request.top_k,
            len(results),
            embedding_batch.provider,
            embedding_batch.model,
            embedding_latency_ms,
            vector_store_latency_ms,
            total_latency_ms,
        )
        return RetrievalSearchResponse(
            query=query,
            top_k=request.top_k,
            embedding_provider=embedding_batch.provider,
            embedding_model=embedding_batch.model,
            vector_store="qdrant",
            vector_collection=vector_collection,
            embedding_latency_ms=embedding_latency_ms,
            vector_store_latency_ms=vector_store_latency_ms,
            total_latency_ms=total_latency_ms,
            records=records,
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


def _log_failure(
    request: RetrievalSearchRequest,
    failure_stage: str,
    total_started_at: float,
    exc: Exception,
) -> None:
    """query 和异常 message 都可能含私密内容，失败日志只记录阶段与异常类型。"""
    logger.error(
        "request_id=%s owner_user_id=%s kb_id=%s "
        "failure_stage=%s total_latency_ms=%s error_type=%s",
        request_id_for_log(),
        request.owner_user_id,
        request.kb_id,
        failure_stage,
        elapsed_ms(total_started_at),
        type(exc).__name__,
    )


retrieval_service = RetrievalService()
