import logging
from time import perf_counter

from app.clients.minio_client import minio_object_reader
from app.clients.qdrant_client import QdrantChunkVectorStore
from app.config.settings import get_settings
from app.document_loader.factory import load_document
from app.embedding.factory import create_embedding_provider
from app.observability.request_context import elapsed_ms, request_id_for_log
from app.schemas.document import IndexDocumentRequest, IndexDocumentResponse
from app.splitter.recursive_splitter import RecursiveTextSplitter

logger = logging.getLogger(__name__)

MAX_PREVIEW_LENGTH = 300


class IndexingService:
    """文档索引 pipeline。

    当前阶段范围：
    1. 从 MinIO 下载上传文件
    2. 把文档解析成统一的 page 结构
    3. 按 citation 友好的方式切分 chunk
    4. 如果配置了 embedding provider，则生成向量
    5. 将 chunk/vector/payload 写入 Qdrant，并返回索引统计

    Java 只有收到 INDEXED 才会把索引任务标记为成功，避免把
    “只完成 embedding”误认为“已经可检索”。
    """

    def __init__(self) -> None:
        settings = get_settings()
        self._settings = settings
        self._splitter = RecursiveTextSplitter(
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
        )
        self._embedding_provider = create_embedding_provider(settings)
        # Qdrant client 延迟创建：只有真正生成了 embedding 并准备写向量时才连接。
        # 这样 EMBEDDING_PROVIDER=none 的调试模式仍能只跑解析/切分链路。
        self._vector_store: QdrantChunkVectorStore | None = None

    def submit_indexing(self, request: IndexDocumentRequest) -> IndexDocumentResponse:
        total_started_at = perf_counter()

        # Java 只发送对象存储位置，不直接发送文件字节。
        # 这样服务边界更轻，也更接近真实 worker 从对象存储拉文件的做法。
        download_started_at = perf_counter()
        try:
            file_bytes = minio_object_reader.read_object(request.bucket, request.object_key)
        except Exception as exc:
            _log_failure(request, "download", total_started_at, exc)
            raise
        download_latency_ms = elapsed_ms(download_started_at)

        # loader factory 根据文件扩展名选择解析器，把 PDF/Word/TXT/Markdown
        # 统一转换成 ParsedDocument。后面的切分和 embedding 不需要关心原始格式。
        parse_started_at = perf_counter()
        try:
            parsed_document = load_document(request.file_name, file_bytes)
        except Exception as exc:
            _log_failure(request, "parse", total_started_at, exc)
            raise
        parse_latency_ms = elapsed_ms(parse_started_at)

        # chunk 是 Qdrant point 的最小单位。这里保留 page_no/char offset，
        # 写入 payload 后，后续回答引用才能定位到具体文档页和片段。
        split_started_at = perf_counter()
        try:
            chunks = self._splitter.split_document(parsed_document)
        except Exception as exc:
            _log_failure(request, "split", total_started_at, exc)
            raise
        split_latency_ms = elapsed_ms(split_started_at)

        # V2 默认使用本地 BGE 模型生成向量。只有向量成功写入 Qdrant 后，
        # 这个任务才算真正进入“可检索索引”状态。
        embedding_started_at = perf_counter()
        try:
            embedding_batch = self._embedding_provider.embed_texts([chunk.text for chunk in chunks])
        except Exception as exc:
            _log_failure(request, "embedding", total_started_at, exc)
            raise
        embedding_latency_ms = elapsed_ms(embedding_started_at)
        if embedding_batch.embedded_count == 0:
            # EMBEDDING_PROVIDER=none 仍然保留为调试模式：可以单独验证
            # “MinIO 下载 -> 文档解析 -> chunk 切分”链路。Java 的 V2 索引任务
            # 不会把 CHUNKED 当成成功，因为它还没有可检索的 Qdrant 数据。
            text = parsed_document.text
            total_latency_ms = elapsed_ms(total_started_at)
            logger.info(
                "request_id=%s operation=index_document task_id=%s document_id=%s kb_id=%s "
                "status=CHUNKED page_count=%s char_count=%s chunk_count=%s provider=%s "
                "download_latency_ms=%s parse_latency_ms=%s split_latency_ms=%s "
                "embedding_latency_ms=%s vector_store_latency_ms=0 total_latency_ms=%s",
                request_id_for_log(),
                request.task_id,
                request.document_id,
                request.kb_id,
                parsed_document.page_count,
                parsed_document.char_count,
                len(chunks),
                embedding_batch.provider,
                download_latency_ms,
                parse_latency_ms,
                split_latency_ms,
                embedding_latency_ms,
                total_latency_ms,
            )
            return IndexDocumentResponse(
                task_id=request.task_id,
                document_id=request.document_id,
                status="CHUNKED",
                message=_message("CHUNKED"),
                page_count=parsed_document.page_count,
                char_count=parsed_document.char_count,
                chunk_count=len(chunks),
                embedded_chunk_count=0,
                indexed_chunk_count=0,
                embedding_provider=embedding_batch.provider,
                embedding_model=embedding_batch.model,
                vector_dim=embedding_batch.vector_dim,
                vector_store=None,
                vector_collection=None,
                text_preview=_preview(text),
                chunk_preview=chunks[0].text if chunks else None,
                download_latency_ms=download_latency_ms,
                parse_latency_ms=parse_latency_ms,
                split_latency_ms=split_latency_ms,
                embedding_latency_ms=embedding_latency_ms,
                vector_store_latency_ms=0,
                total_latency_ms=total_latency_ms,
            )

        vector_store_started_at = perf_counter()
        try:
            qdrant_result = self._get_vector_store().upsert_chunks(request, chunks, embedding_batch)
        except Exception as exc:
            _log_failure(request, "vector_store", total_started_at, exc)
            raise
        vector_store_latency_ms = elapsed_ms(vector_store_started_at)
        text = parsed_document.text
        status = "INDEXED"
        total_latency_ms = elapsed_ms(total_started_at)

        logger.info(
            "request_id=%s operation=index_document task_id=%s document_id=%s kb_id=%s "
            "page_count=%s char_count=%s chunk_count=%s embedded_chunk_count=%s "
            "indexed_chunk_count=%s vector_dim=%s provider=%s collection=%s "
            "download_latency_ms=%s parse_latency_ms=%s split_latency_ms=%s "
            "embedding_latency_ms=%s vector_store_latency_ms=%s total_latency_ms=%s",
            request_id_for_log(),
            request.task_id,
            request.document_id,
            request.kb_id,
            parsed_document.page_count,
            parsed_document.char_count,
            len(chunks),
            embedding_batch.embedded_count,
            qdrant_result.indexed_count,
            embedding_batch.vector_dim,
            embedding_batch.provider,
            qdrant_result.collection_name,
            download_latency_ms,
            parse_latency_ms,
            split_latency_ms,
            embedding_latency_ms,
            vector_store_latency_ms,
            total_latency_ms,
        )
        return IndexDocumentResponse(
            task_id=request.task_id,
            document_id=request.document_id,
            status=status,
            message=_message(status),
            page_count=parsed_document.page_count,
            char_count=parsed_document.char_count,
            chunk_count=len(chunks),
            embedded_chunk_count=embedding_batch.embedded_count,
            indexed_chunk_count=qdrant_result.indexed_count,
            embedding_provider=embedding_batch.provider,
            embedding_model=embedding_batch.model,
            vector_dim=embedding_batch.vector_dim,
            vector_store="qdrant",
            vector_collection=qdrant_result.collection_name,
            text_preview=_preview(text),
            chunk_preview=chunks[0].text if chunks else None,
            download_latency_ms=download_latency_ms,
            parse_latency_ms=parse_latency_ms,
            split_latency_ms=split_latency_ms,
            embedding_latency_ms=embedding_latency_ms,
            vector_store_latency_ms=vector_store_latency_ms,
            total_latency_ms=total_latency_ms,
        )

    def _get_vector_store(self) -> QdrantChunkVectorStore:
        if self._vector_store is None:
            self._vector_store = QdrantChunkVectorStore(self._settings)
        return self._vector_store


indexing_service = IndexingService()


def _preview(text: str) -> str:
    # preview 只用于日志和接口调试；全文后续会进入向量 payload，
    # 不应该由这个接口完整返回。
    compact_text = " ".join(text.split())
    return compact_text[:MAX_PREVIEW_LENGTH]


def _message(status: str) -> str:
    if status == "INDEXED":
        return "文档已下载、解析、切分、生成 embedding 并写入 Qdrant。"
    if status == "EMBEDDED":
        return "文档已下载、解析、切分并生成 embedding，但尚未写入 Qdrant。"
    return "文档已下载、解析并切分；当前未启用 embedding，未写入 Qdrant。"


def _log_failure(
    request: IndexDocumentRequest,
    failure_stage: str,
    total_started_at: float,
    exc: Exception,
) -> None:
    """失败日志只保留定位阶段必需的字段。

    不记录 object key、文档内容或异常 message：这些数据可能包含存储路径、
    密钥或用户正文。原异常由调用方按原有语义处理。
    """
    logger.error(
        "request_id=%s task_id=%s document_id=%s kb_id=%s "
        "failure_stage=%s total_latency_ms=%s error_type=%s",
        request_id_for_log(),
        request.task_id,
        request.document_id,
        request.kb_id,
        failure_stage,
        elapsed_ms(total_started_at),
        type(exc).__name__,
    )
