from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


DEFAULT_EMBEDDING_MODEL_NAME = "BAAI/bge-small-zh-v1.5"
DEFAULT_EMBEDDING_MODEL_PATH = (
    Path(__file__).resolve().parents[3] / ".models" / "bge-small-zh-v1.5"
)
DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"


class Settings(BaseSettings):
    """AI 服务的环境变量配置。

    配置可以来自 shell 环境变量，也可以来自本地 .env 文件。
    这对后续 Docker 部署很重要：同一份代码可以通过配置切换主机路径、
    挂载模型目录和容器服务名，而不需要改代码。
    """

    app_name: str = "rag-ai-service"
    app_env: str = "local"

    minio_endpoint: str = "localhost:9000"
    minio_access_key: str
    minio_secret_key: str
    minio_secure: bool = False

    # Qdrant 是当前 V2 的向量索引落地点。
    # URL 和 collection 名放在配置里，方便本地 Docker、后续 Docker Compose
    # 或测试环境通过环境变量切换，不把部署地址写死在 pipeline 代码中。
    qdrant_url: str = "http://localhost:6333"
    qdrant_collection: str = "rag_chunks_v1"

    # 切分参数放在配置里，而不是写死成常量。
    # V2 检索评测会对比不同 chunk_size/chunk_overlap 的效果。
    chunk_size: int = 800
    chunk_overlap: int = 120

    # V1 默认使用本地 embedding 模型，而不是线上 API。
    # 模型目录默认放在项目根目录 .models/bge-small-zh-v1.5；
    # Docker 部署时也可以把同一个目录挂载进容器，并通过环境变量覆盖路径。
    embedding_provider: str = "local"
    embedding_model_path: str | None = str(DEFAULT_EMBEDDING_MODEL_PATH)
    embedding_model_name: str | None = DEFAULT_EMBEDDING_MODEL_NAME
    embedding_device: str = "cpu"
    embedding_normalize: bool = True
    embedding_batch_size: int = 32

    # 最小 RAG 生成只支持 OpenAI-compatible Chat Completions。
    # API key 和模型名必须通过环境变量传入，避免把任何线上密钥写进仓库。
    llm_provider: str = "none"
    llm_base_url: str | None = None
    llm_api_key: str | None = None
    llm_model: str | None = None
    llm_temperature: float = 0.2
    llm_max_tokens: int = 800
    llm_timeout_seconds: float = 60.0

    # 本地开发常见场景：shell 已经有 DEEPSEEK_API_KEY。
    # 显式 LLM_* 配置仍然优先；没有 LLM_* 时，自动按 DeepSeek
    # OpenAI-compatible Chat Completions 读取，避免每次启动手动转写变量。
    deepseek_api_key: str | None = None

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    def resolved_llm_provider(self) -> str:
        if self.llm_provider != "none":
            return self.llm_provider
        if self.deepseek_api_key:
            return "openai-compatible"
        return self.llm_provider

    def resolved_llm_base_url(self) -> str | None:
        if self.llm_base_url:
            return self.llm_base_url
        if self.deepseek_api_key:
            return DEFAULT_DEEPSEEK_BASE_URL
        return None

    def resolved_llm_api_key(self) -> str | None:
        return self.llm_api_key or self.deepseek_api_key

    def resolved_llm_model(self) -> str | None:
        if self.llm_model:
            return self.llm_model
        if self.deepseek_api_key:
            return DEFAULT_DEEPSEEK_MODEL
        return None


@lru_cache
def get_settings() -> Settings:
    """缓存配置对象，让 clients/pipelines 复用同一份解析结果。"""
    return Settings()
