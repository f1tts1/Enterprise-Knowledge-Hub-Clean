from __future__ import annotations

import logging
import re
from contextvars import ContextVar, Token
from time import perf_counter
from typing import Any, Awaitable, Callable
from uuid import uuid4

from starlette.datastructures import Headers, MutableHeaders


logger = logging.getLogger(__name__)

REQUEST_ID_HEADER = "X-Request-Id"
MAX_REQUEST_ID_LENGTH = 64
_REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")
_request_id_context: ContextVar[str | None] = ContextVar("request_id", default=None)

AsgiMessage = dict[str, Any]
AsgiReceive = Callable[[], Awaitable[AsgiMessage]]
AsgiSend = Callable[[AsgiMessage], Awaitable[None]]
AsgiApp = Callable[[dict[str, Any], AsgiReceive, AsgiSend], Awaitable[None]]


class RequestContextMiddleware:
    """为每个 HTTP 请求建立 requestId，并写回响应头。

    Java 会把入口请求的 ``X-Request-Id`` 传给 AI 服务。这里只接受长度不超过 64
    且适合安全写入日志的标识；空值、控制字符或其它非法值都会替换为 UUID，避免
    外部输入造成日志注入。ContextVar 可以在异步请求间隔离关联信息。
    """

    def __init__(self, app: AsgiApp) -> None:
        self._app = app

    async def __call__(
        self,
        scope: dict[str, Any],
        receive: AsgiReceive,
        send: AsgiSend,
    ) -> None:
        if scope.get("type") != "http":
            await self._app(scope, receive, send)
            return

        request_id = normalize_request_id(Headers(scope=scope).get(REQUEST_ID_HEADER))
        token = set_request_id(request_id)
        started_at = perf_counter()
        status_code = 500

        async def send_with_request_id(message: AsgiMessage) -> None:
            nonlocal status_code
            if message.get("type") == "http.response.start":
                status_code = int(message.get("status", 500))
                MutableHeaders(scope=message)[REQUEST_ID_HEADER] = request_id
            await send(message)

        try:
            await self._app(scope, receive, send_with_request_id)
        finally:
            duration_ms = elapsed_ms(started_at)
            logger.info(
                "request_id=%s method=%s path=%s status=%s duration_ms=%s",
                request_id,
                scope.get("method", ""),
                scope.get("path", ""),
                status_code,
                duration_ms,
            )
            reset_request_id(token)


def normalize_request_id(value: str | None) -> str:
    """返回可安全记录的 requestId；非法输入统一替换为 UUID。"""
    candidate = value.strip() if value else None
    if (
        candidate
        and len(candidate) <= MAX_REQUEST_ID_LENGTH
        and _REQUEST_ID_PATTERN.fullmatch(candidate)
    ):
        return candidate
    return str(uuid4())


def get_request_id() -> str | None:
    return _request_id_context.get()


def request_id_for_log() -> str:
    """业务日志始终显式携带 request_id，即使服务被单元测试直接调用。"""
    return get_request_id() or "unavailable"


def set_request_id(request_id: str) -> Token[str | None]:
    return _request_id_context.set(request_id)


def reset_request_id(token: Token[str | None]) -> None:
    _request_id_context.reset(token)


def elapsed_ms(started_at: float) -> int:
    """使用单调时钟计算非负整数毫秒，适合接口字段和结构化日志。"""
    return max(0, round((perf_counter() - started_at) * 1000))
