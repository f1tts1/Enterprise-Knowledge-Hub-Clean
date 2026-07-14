from __future__ import annotations

import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid5

from app.config.settings import Settings
from app.embedding.base import EmbeddingBatch
from app.schemas.document import IndexDocumentRequest
from app.splitter.base import TextChunk


# Qdrant 的 point id 推荐使用整数或 UUID。这里用固定 namespace + 文档/chunk
# 信息生成稳定 UUID，保证同一份文档重试索引时会覆盖同一批 point，
# 而不是每次失败重试都向 Qdrant 追加重复向量。
POINT_ID_NAMESPACE = UUID("4c61c2b7-4f9d-45c8-9a72-2f1fd78df4f8")
FILTER_PAYLOAD_INDEX_FIELDS = ("ownerUserId", "kbId", "docId")
ALREADY_EXISTS_ERROR_PATTERNS = (
    "already exists",
    "already exist",
    "status_code=409",
    "status code 409",
    "unexpected response: 409",
)


@dataclass(frozen=True)
class QdrantIndexResult:
    """一次 Qdrant 写入的结果。

    indexed_count 表示本次成功 upsert 的 chunk 数。Java 会用它和 chunk_count
    对齐校验，避免只生成了 embedding、但没有真正写入向量库时误标成功。
    """

    collection_name: str
    indexed_count: int


@dataclass(frozen=True)
class QdrantSearchResult:
    """一次 Qdrant 检索命中的 chunk。

    这里把 Qdrant 原始 point 转成项目自己的结构，避免上层 retrieval service
    直接依赖 qdrant-client 的返回对象。后续如果换向量库，影响会集中在本文件。
    """

    point_id: str
    score: float
    payload: dict[str, Any]


class VectorStoreError(RuntimeError):
    """向量库访问失败。

    qdrant-client 的底层异常会包含 HTTP 细节，例如 502、连接失败等。
    对 API 层来说，这些都属于“外部向量库依赖不可用或响应异常”，
    统一包装后更容易转换成 502，并让 Java 把索引任务标记为 FAILED。
    """


class QdrantChunkVectorStore:
    """面向文档 chunk 的 Qdrant 写入封装。

    这个类只处理“向量库如何写”的细节：创建 collection、构造 point、
    删除同文档旧 point、upsert 新 point。索引 pipeline 本身仍然只关心
    文档解析、切分和 embedding，避免 AI 流程和具体向量库 API 耦合太深。
    """

    def __init__(self, settings: Settings) -> None:
        # qdrant-client 是运行时依赖。放在构造函数中导入，能让语法检查、
        # 静态阅读和不启动 AI 服务的场景不受本地 Python 环境是否安装依赖影响。
        from qdrant_client import QdrantClient

        self.collection_name = settings.qdrant_collection
        self._client = QdrantClient(url=settings.qdrant_url)
        self._payload_indexes_ensured = False
        self._schema_lock = threading.Lock()

    def upsert_chunks(
            self,
            request: IndexDocumentRequest,
            chunks: list[TextChunk],
            embedding_batch: EmbeddingBatch,
    ) -> QdrantIndexResult:
        """把一份文档的 chunk 和向量写入 Qdrant。

        写入前先删除同业务文档的旧 point，使任务重试具备幂等性：
        如果某次重试切出的 chunk 数变少，旧的高序号 chunk 不会残留在检索结果里。
        """
        self._validate_vectors(chunks, embedding_batch)
        try:
            self._ensure_collection(embedding_batch.vector_dim)
            self._ensure_payload_indexes()
            self._delete_existing_document_points(
                request.owner_user_id,
                request.kb_id,
                request.document_id,
            )

            points = [
                self._build_point(request, chunk, vector, embedding_batch)
                for chunk, vector in zip(chunks, embedding_batch.vectors, strict=True)
            ]
            self._client.upsert(
                collection_name=self.collection_name,
                points=points,
                wait=True,
            )
        except Exception as exc:
            raise VectorStoreError(f"Qdrant 写入失败: {exc}") from exc
        return QdrantIndexResult(
            collection_name=self.collection_name,
            indexed_count=len(points),
        )

    def search_chunks(
            self,
            owner_user_id: int,
            kb_id: int,
            query_vector: list[float],
            limit: int,
    ) -> list[QdrantSearchResult]:
        """在指定用户和知识库范围内检索 chunk。

        ownerUserId + kbId filter 是本项目区别于普通 RAG demo 的核心边界：
        Java 会先校验知识库归属，Qdrant 查询阶段仍然必须再过滤一次，
        避免向量召回跨用户或跨知识库的数据。
        """
        if not query_vector:
            raise ValueError("query_vector 不能为空")
        if limit <= 0:
            raise ValueError("limit 必须大于 0")

        from qdrant_client import models

        query_filter = models.Filter(
            must=[
                models.FieldCondition(
                    key="ownerUserId",
                    match=models.MatchValue(value=owner_user_id),
                ),
                models.FieldCondition(
                    key="kbId",
                    match=models.MatchValue(value=kb_id),
                ),
            ]
        )
        try:
            if not self._client.collection_exists(self.collection_name):
                return []
            self._ensure_payload_indexes()
            points = self._search_points(query_vector, query_filter, limit)
        except Exception as exc:
            raise VectorStoreError(f"Qdrant 检索失败: {exc}") from exc
        return [
            QdrantSearchResult(
                point_id=str(point.id),
                score=float(point.score),
                payload=dict(point.payload or {}),
            )
            for point in points
        ]

    def delete_document_points(
            self,
            owner_user_id: int,
            kb_id: int,
            document_id: int,
    ) -> bool:
        """删除某个业务文档在 Qdrant 中的全部 chunk vectors。

        删除接口虽然只给 Java 内部调用，但仍然使用 ownerUserId + kbId + docId
        三个字段作为 filter。这样即使未来出现错误调用，也不会因为只传 docId
        就跨用户或跨知识库删除不属于当前业务边界的数据。

        collection 不存在时视为幂等成功：说明当前环境还没有任何可删除向量，
        Java 删除文档不应该因为“本来就没有 collection”而失败。返回值仅表示
        collection 在本次删除请求时是否存在，不再为了日志额外 count point。
        """
        if owner_user_id <= 0:
            raise ValueError("owner_user_id 必须大于 0")
        if kb_id <= 0:
            raise ValueError("kb_id 必须大于 0")
        if document_id <= 0:
            raise ValueError("document_id 必须大于 0")

        from qdrant_client import models

        try:
            if not self._client.collection_exists(self.collection_name):
                return False

            self._ensure_payload_indexes()
            document_filter = self._document_filter(owner_user_id, kb_id, document_id)
            self._client.delete(
                collection_name=self.collection_name,
                points_selector=models.FilterSelector(filter=document_filter),
                wait=True,
            )
        except Exception as exc:
            raise VectorStoreError(f"Qdrant 删除文档向量失败: {exc}") from exc

        return True

    def _validate_vectors(self, chunks: list[TextChunk], embedding_batch: EmbeddingBatch) -> None:
        # Qdrant 写入必须满足 chunk 与 vector 一一对应，否则后续引用会出现
        # “向量命中 A 文本，payload 却指向 B 文本”的严重错配。
        if not chunks:
            raise ValueError("没有可写入 Qdrant 的 chunk")
        if embedding_batch.embedded_count != len(chunks):
            raise ValueError(
                f"embedding 数量与 chunk 数量不一致: embedded={embedding_batch.embedded_count}, chunks={len(chunks)}"
            )
        if embedding_batch.vector_dim <= 0:
            raise ValueError("embedding vector_dim 必须大于 0")

    def _ensure_collection(self, vector_dim: int) -> None:
        # collection 不存在时自动创建，降低本地演示门槛。向量维度来自模型真实输出，
        # 避免手写 512 后未来切换模型时忘记同步配置。
        if self._client.collection_exists(self.collection_name):
            return

        from qdrant_client import models

        # 首次并发上传文档时，多个索引请求可能同时发现 collection 不存在。
        # 进程内用锁收敛竞态；多 worker 场景仍可能撞到 Qdrant 的“已存在”，
        # 因此把已存在视为幂等成功。
        with self._schema_lock:
            if self._client.collection_exists(self.collection_name):
                return
            try:
                self._client.create_collection(
                    collection_name=self.collection_name,
                    vectors_config=models.VectorParams(
                        size=vector_dim,
                        distance=models.Distance.COSINE,
                    ),
                )
            except Exception as exc:
                if _is_already_exists_error(exc):
                    return
                raise

    def _ensure_payload_indexes(self) -> None:
        # 检索和删除都依赖 ownerUserId/kbId/docId filter。为这些字段创建
        # payload index，可以避免数据量增长后每次过滤都退化为全量扫描。
        if self._payload_indexes_ensured:
            return

        from qdrant_client import models

        # _payload_indexes_ensured 只能避免本进程重复创建；首次并发索引时，
        # 两个请求可能同时进入初始化。这里用双重检查 + 已存在异常幂等处理，
        # 避免其中一个任务因为 schema 初始化竞态被误标为索引失败。
        with self._schema_lock:
            if self._payload_indexes_ensured:
                return

            collection_info = self._client.get_collection(self.collection_name)
            payload_schema = dict(collection_info.payload_schema or {})
            missing_fields = [
                field_name
                for field_name in FILTER_PAYLOAD_INDEX_FIELDS
                if field_name not in payload_schema
            ]
            for field_name in missing_fields:
                try:
                    self._client.create_payload_index(
                        collection_name=self.collection_name,
                        field_name=field_name,
                        field_schema=models.PayloadSchemaType.INTEGER,
                        wait=True,
                    )
                except Exception as exc:
                    if _is_already_exists_error(exc):
                        continue
                    raise
            self._payload_indexes_ensured = True

    def _delete_existing_document_points(self, owner_user_id: int, kb_id: int, document_id: int) -> None:
        # Redis 重投、人工重试都可能再次处理同一份业务文档。
        # point id 已经由 documentId + chunkIndex 确定生成，重复 upsert 会覆盖同序号 chunk；
        # 这里额外先删除旧 point，是为了处理“重试后 chunk 数变少”的场景，避免高序号旧 chunk 残留。
        #
        # 过滤条件和删除接口保持一致，始终使用 ownerUserId + kbId + docId。
        # 即使当前 MySQL documentId 是全局自增，也不要让向量库写入路径只依赖 docId；
        # 这样后续做数据迁移、多环境导入或租户扩展时，边界仍然清晰。
        from qdrant_client import models

        self._client.delete(
            collection_name=self.collection_name,
            points_selector=models.FilterSelector(
                filter=self._document_filter(owner_user_id, kb_id, document_id)
            ),
            wait=True,
        )

    def _document_filter(self, owner_user_id: int, kb_id: int, document_id: int) -> Any:
        from qdrant_client import models

        return models.Filter(
            must=[
                models.FieldCondition(
                    key="ownerUserId",
                    match=models.MatchValue(value=owner_user_id),
                ),
                models.FieldCondition(
                    key="kbId",
                    match=models.MatchValue(value=kb_id),
                ),
                models.FieldCondition(
                    key="docId",
                    match=models.MatchValue(value=document_id),
                ),
            ]
        )

    def _build_point(
            self,
            request: IndexDocumentRequest,
            chunk: TextChunk,
            vector: list[float],
            embedding_batch: EmbeddingBatch,
    ):
        from qdrant_client import models

        # payload 里必须保存权限字段 ownerUserId/kbId。后续检索不能只依赖
        # Java 事后过滤，而应该在 Qdrant 查询阶段就把越权 chunk 排除掉。
        payload = {
            "ownerUserId": request.owner_user_id,
            "kbId": request.kb_id,
            "docId": request.document_id,
            "taskId": request.task_id,
            "chunkId": _chunk_id(request.document_id, chunk.chunk_index),
            "fileName": request.file_name,
            "contentType": request.content_type,
            "fileSize": request.file_size,
            "bucket": request.bucket,
            "objectKey": request.object_key,
            "checksumSha256": request.checksum_sha256,
            "pageNo": chunk.page_no,
            "chunkIndex": chunk.chunk_index,
            "charStart": chunk.char_start,
            "charEnd": chunk.char_end,
            "text": chunk.text,
            "embeddingProvider": embedding_batch.provider,
            "embeddingModel": embedding_batch.model,
            "createdAt": datetime.now(timezone.utc).isoformat(),
        }
        return models.PointStruct(
            id=_point_id(request.document_id, chunk.chunk_index),
            vector=vector,
            payload=payload,
        )

    def _search_points(self, query_vector: list[float], query_filter: Any, limit: int) -> list[Any]:
        # qdrant-client 版本之间搜索 API 有过演进。优先使用 search，
        # 如果当前版本只提供 query_points，则走兼容分支，避免把项目锁死在单一小版本。
        if hasattr(self._client, "search"):
            return self._client.search(
                collection_name=self.collection_name,
                query_vector=query_vector,
                query_filter=query_filter,
                limit=limit,
                with_payload=True,
            )

        response = self._client.query_points(
            collection_name=self.collection_name,
            query=query_vector,
            query_filter=query_filter,
            limit=limit,
            with_payload=True,
        )
        return list(response.points)


def _chunk_id(document_id: int, chunk_index: int) -> str:
    return f"doc-{document_id}-chunk-{chunk_index}"


def _point_id(document_id: int, chunk_index: int) -> str:
    return str(uuid5(POINT_ID_NAMESPACE, _chunk_id(document_id, chunk_index)))


def _is_already_exists_error(exc: Exception) -> bool:
    message = str(exc).lower()
    return any(pattern in message for pattern in ALREADY_EXISTS_ERROR_PATTERNS)
