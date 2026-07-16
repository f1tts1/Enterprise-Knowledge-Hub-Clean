from __future__ import annotations

import os
import unittest
from uuid import UUID


# 当前配置对象同时包含 MinIO 运行参数；单元测试只使用内存 fake，提前提供占位值，
# 避免导入 service 时依赖开发者本地 .env。
os.environ.setdefault("MINIO_ACCESS_KEY", "unit-test-access-key")
os.environ.setdefault("MINIO_SECRET_KEY", "unit-test-secret-key")

from app.observability.request_context import (  # noqa: E402
    REQUEST_ID_HEADER,
    RequestContextMiddleware,
    get_request_id,
    normalize_request_id,
)


class RequestContextTest(unittest.IsolatedAsyncioTestCase):
    def test_normalize_request_id_accepts_safe_value_and_rejects_log_injection(self) -> None:
        self.assertEqual("request-123", normalize_request_id("  request-123  "))

        generated = normalize_request_id("request-id\nforged-log-line")
        self.assertEqual(generated, str(UUID(generated)))

        too_long = normalize_request_id("a" * 65)
        self.assertEqual(too_long, str(UUID(too_long)))

    async def test_middleware_propagates_request_id_and_writes_response_header(self) -> None:
        observed_request_ids: list[str | None] = []
        sent_messages: list[dict] = []

        async def downstream(scope, receive, send) -> None:
            observed_request_ids.append(get_request_id())
            await send({"type": "http.response.start", "status": 204, "headers": []})
            await send({"type": "http.response.body", "body": b""})

        middleware = RequestContextMiddleware(downstream)
        scope = {
            "type": "http",
            "method": "POST",
            "path": "/api/v1/retrieval/search",
            "headers": [(b"x-request-id", b"java-request-1")],
        }

        async def receive() -> dict:
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message: dict) -> None:
            sent_messages.append(message)

        with self.assertLogs("app.observability.request_context", level="INFO") as logs:
            await middleware(scope, receive, send)

        self.assertEqual(["java-request-1"], observed_request_ids)
        response_headers = dict(sent_messages[0]["headers"])
        self.assertEqual(b"java-request-1", response_headers[REQUEST_ID_HEADER.lower().encode()])
        self.assertIsNone(get_request_id())
        self.assertIn("request_id=java-request-1", "\n".join(logs.output))


if __name__ == "__main__":
    unittest.main()
