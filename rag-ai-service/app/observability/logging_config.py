from __future__ import annotations

import logging


def configure_application_logging() -> None:
    """确保 ``app.*`` INFO 业务日志在 Uvicorn 下实际可见。

    Uvicorn 默认只为自身 logger 配置 handler，普通模块 logger 的 INFO 记录可能被
    root 的 WARNING 级别丢弃。这里仅配置本服务 ``app`` 命名空间，不改第三方库级别。
    """
    application_logger = logging.getLogger("app")
    application_logger.setLevel(logging.INFO)
    if not application_logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(
            logging.Formatter(
                fmt="%(asctime)s level=%(levelname)s logger=%(name)s %(message)s",
            )
        )
        application_logger.addHandler(handler)
    application_logger.propagate = False
