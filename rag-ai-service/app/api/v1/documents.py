from fastapi import APIRouter, HTTPException
from minio.error import S3Error

from app.clients.qdrant_client import QdrantChunkVectorStore, VectorStoreError
from app.config.settings import get_settings
from app.document_loader.factory import UnsupportedDocumentTypeError
from app.indexing.service import indexing_service
from app.schemas.document import (
    DeleteDocumentVectorsRequest,
    DeleteDocumentVectorsResponse,
    IndexDocumentRequest,
    IndexDocumentResponse,
)

router = APIRouter(prefix="/api/v1/documents", tags=["documents"])
_vector_store: QdrantChunkVectorStore | None = None


@router.post("/index", response_model=IndexDocumentResponse)
async def index_document(request: IndexDocumentRequest) -> IndexDocumentResponse:
    """Java 后端在文档上传事务提交后调用的入口。

    当前接口负责完整文档索引：从 MinIO 下载文件、解析文本、切分 chunk、
    生成 embedding 并写入 Qdrant。用户权限不在 Python 里判断；Java 在发送
    请求前已经校验了知识库和文档归属。
    """
    try:
        return indexing_service.submit_indexing(request)
    except UnsupportedDocumentTypeError as exc:
        # 文件类型不支持是请求问题，Java 会把任务标记为 FAILED，
        # 同时错误原因会落到 indexing_task.error_message。
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except S3Error as exc:
        # MinIO 下载失败通常是对象不存在、签名配置错误或对象存储不可用。
        # 用 502 表达“上游存储依赖失败”，比 500 更利于 Java 侧排查。
        raise HTTPException(status_code=502, detail=f"Failed to download object from MinIO: {exc}") from exc
    except VectorStoreError as exc:
        # Qdrant 是索引链路的外部依赖。连接失败、端口映射错误、collection API
        # 返回 502 等情况都应该让 Java 看到清晰的依赖失败原因。
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@router.post("/delete-vectors", response_model=DeleteDocumentVectorsResponse)
async def delete_document_vectors(request: DeleteDocumentVectorsRequest) -> DeleteDocumentVectorsResponse:
    """Java 删除文档后调用的 Qdrant 清理入口。

    这个接口只删除向量库中的 chunk vectors，不删除 MinIO object，也不修改 MySQL。
    Java 仍然是业务状态的唯一维护者；Python 只负责按 Java 传入的业务归属字段
    执行 ownerUserId + kbId + docId filter 删除。
    """
    try:
        vector_store = _get_vector_store()
        collection_existed = vector_store.delete_document_points(
            owner_user_id=request.owner_user_id,
            kb_id=request.kb_id,
            document_id=request.document_id,
        )
        return DeleteDocumentVectorsResponse(
            document_id=request.document_id,
            status="DELETED",
            message="文档对应的 Qdrant vectors 删除请求已按幂等语义完成。",
            vector_collection=vector_store.collection_name,
            collection_existed=collection_existed,
        )
    except VectorStoreError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


def _get_vector_store() -> QdrantChunkVectorStore:
    # 删除向量不需要单独 service 包装；route 直接调用向量库封装即可。
    # 仍然延迟创建 client，避免单纯加载 FastAPI app 时强依赖 Qdrant 已启动。
    global _vector_store
    if _vector_store is None:
        _vector_store = QdrantChunkVectorStore(get_settings())
    return _vector_store
