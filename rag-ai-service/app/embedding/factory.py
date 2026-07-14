from app.config.settings import Settings
from app.embedding.base import DisabledEmbeddingProvider, EmbeddingProvider
from app.embedding.local_bge import LocalBgeEmbeddingProvider


def create_embedding_provider(settings: Settings) -> EmbeddingProvider:
    """根据配置创建 embedding 提供方。

    这里是后续扩展的关键边界：
    - local：V1 默认方案，使用下载到本机的 BGE 模型
    - none/disabled：调试解析和切分时临时关闭 embedding

    暂时不保留未实现的线上 embedding 分支，避免配置里出现看起来可用、
    实际会在运行时报错的“假功能”。
    """

    provider = settings.embedding_provider.lower().strip()
    if provider in {"", "none", "disabled"}:
        return DisabledEmbeddingProvider()

    if provider == "local":
        return LocalBgeEmbeddingProvider(
            model_path=settings.embedding_model_path or "",
            model_name=settings.embedding_model_name,
            device=settings.embedding_device,
            normalize=settings.embedding_normalize,
            batch_size=settings.embedding_batch_size,
        )

    raise ValueError(f"不支持的 EMBEDDING_PROVIDER: {settings.embedding_provider}")
