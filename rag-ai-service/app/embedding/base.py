from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class EmbeddingBatch:
    """一次 embedding 调用的结果。

    这里不只返回 vectors，还返回 provider、model、vector_dim 等元数据。
    后续创建 Qdrant collection 时必须知道 vector_dim，否则会出现向量维度不匹配。
    """

    vectors: list[list[float]]
    provider: str
    model: str | None
    vector_dim: int

    @property
    def embedded_count(self) -> int:
        return len(self.vectors)


class EmbeddingProvider(ABC):
    """Embedding 提供方统一接口。

    当前真实实现是本地 BGE 模型。这里保留接口边界，是为了后续如果要接线上
    embedding API，可以新增实现类，而不改索引 pipeline 的主体流程。
    """

    @abstractmethod
    def embed_texts(self, texts: list[str]) -> EmbeddingBatch:
        """把一批文本转换成向量。"""


class DisabledEmbeddingProvider(EmbeddingProvider):
    """未启用 embedding 时使用的空实现。

    V1 默认已经切到本地模型。这个空实现只用于排查问题：
    当你只想验证“上传 -> MinIO -> 解析 -> 切分”链路时，
    可以临时设置 EMBEDDING_PROVIDER=none 跳过模型加载。
    """

    def embed_texts(self, texts: list[str]) -> EmbeddingBatch:
        return EmbeddingBatch(
            vectors=[],
            provider="none",
            model=None,
            vector_dim=0,
        )
