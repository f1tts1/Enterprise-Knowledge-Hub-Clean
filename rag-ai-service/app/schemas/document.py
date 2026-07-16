from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class IndexDocumentRequest(BaseModel):
    """Java 发起文档索引任务时传给 Python 的 DTO。"""

    task_id: int = Field(gt=0)
    document_id: int = Field(gt=0)
    kb_id: int = Field(gt=0)
    owner_user_id: int = Field(gt=0)
    file_name: str = Field(min_length=1, max_length=255)
    content_type: str | None = Field(default=None, max_length=128)
    file_size: int = Field(ge=0)
    bucket: str = Field(min_length=1, max_length=128)
    object_key: str = Field(min_length=1, max_length=512)
    checksum_sha256: str | None = Field(default=None, min_length=64, max_length=64)


class IndexDocumentResponse(BaseModel):
    """当前索引阶段 Python 返回给 Java 的进度响应。

    CHUNKED 表示文件已下载、已解析、已切分。
    EMBEDDED 表示已经生成 embedding 向量，但还没有写入 Qdrant。
    INDEXED 表示 chunk/vector/payload 已经写入 Qdrant，可以作为检索数据源。
    """

    task_id: int
    document_id: int
    status: Literal["ACCEPTED", "PARSED", "CHUNKED", "EMBEDDED", "INDEXED"]
    message: str
    page_count: int = 0
    char_count: int = 0
    chunk_count: int = 0
    embedded_chunk_count: int = 0
    indexed_chunk_count: int = 0
    embedding_provider: str | None = None
    embedding_model: str | None = None
    vector_dim: int = 0
    vector_store: str | None = None
    vector_collection: str | None = None
    text_preview: str | None = None
    chunk_preview: str | None = None
    download_latency_ms: int = Field(default=0, ge=0)
    parse_latency_ms: int = Field(default=0, ge=0)
    split_latency_ms: int = Field(default=0, ge=0)
    embedding_latency_ms: int = Field(default=0, ge=0)
    vector_store_latency_ms: int = Field(default=0, ge=0)
    total_latency_ms: int = Field(default=0, ge=0)


class DeleteDocumentVectorsRequest(BaseModel):
    """Java 删除文档后要求 Python 清理 Qdrant vectors 的内部 DTO。

    这里不只传 document_id，而是同时传 owner_user_id 和 kb_id。Python 不做完整
    登录态权限判断，但删除向量时仍必须把业务归属字段下推到 Qdrant filter，
    避免内部调用错误时误删其它用户或其它知识库的数据。
    """

    document_id: int = Field(gt=0)
    kb_id: int = Field(gt=0)
    owner_user_id: int = Field(gt=0)


class DeleteDocumentVectorsResponse(BaseModel):
    """文档 vectors 清理结果。

    如果 collection 不存在，或目标文档本来就没有 vectors，删除请求都视为成功。
    接口不再为了日志额外统计 point 数量，避免删除路径做不必要的向量库读操作。
    """

    document_id: int
    status: Literal["DELETED"]
    message: str
    vector_store: str = "qdrant"
    vector_collection: str
    collection_existed: bool = True
