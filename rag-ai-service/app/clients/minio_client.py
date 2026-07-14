from __future__ import annotations

from urllib.parse import urlparse

from minio import Minio

from app.config.settings import get_settings


class MinioObjectReader:
    """索引 pipeline 使用的 MinIO SDK 简单封装。

    Java 把文件存入 MinIO，然后把 bucket/object_key 发给 Python。
    Python 直接从对象存储读取文件，避免大文件内容在 Java 和 Python 之间传来传去。
    """

    def __init__(self) -> None:
        settings = get_settings()
        endpoint, secure = self._normalize_endpoint(settings.minio_endpoint, settings.minio_secure)
        self._client = Minio(
            endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=secure,
        )

    def read_object(self, bucket: str, object_key: str) -> bytes:
        response = self._client.get_object(bucket, object_key)
        try:
            return response.read()
        finally:
            # MinIO 响应对象持有 HTTP 连接；读取后必须关闭并释放，
            # 避免连续索引任务时连接泄漏。
            response.close()
            response.release_conn()

    def _normalize_endpoint(self, endpoint: str, secure: bool) -> tuple[str, bool]:
        # Python SDK 需要 host:port，再单独传 secure 标记；
        # Java 配置里常见的是 http://localhost:9000。这里同时兼容两种写法。
        if endpoint.startswith("http://") or endpoint.startswith("https://"):
            parsed = urlparse(endpoint)
            return parsed.netloc, parsed.scheme == "https"
        return endpoint, secure


minio_object_reader = MinioObjectReader()
