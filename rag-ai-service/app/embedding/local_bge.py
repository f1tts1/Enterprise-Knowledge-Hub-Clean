from __future__ import annotations

from pathlib import Path
from typing import Any

from app.embedding.base import EmbeddingBatch, EmbeddingProvider


class LocalBgeEmbeddingProvider(EmbeddingProvider):
    """本地 BGE embedding 提供方。

    模型可以放在 Mac 本机目录，也可以通过 Docker volume 挂载到容器。
    这里采用延迟加载：FastAPI 启动时不立刻加载大模型，第一次真正生成向量时再加载。
    """

    def __init__(
        self,
        model_path: str,
        model_name: str | None = None,
        device: str = "cpu",
        normalize: bool = True,
        batch_size: int = 32,
    ) -> None:
        if not model_path:
            raise ValueError("EMBEDDING_MODEL_PATH 不能为空")

        self.model_path = model_path
        self.model_name = model_name or Path(model_path).name
        self.device = device
        self.normalize = normalize
        self.batch_size = batch_size
        self._model: Any | None = None

    def embed_texts(self, texts: list[str]) -> EmbeddingBatch:
        clean_texts = [text for text in texts if text.strip()]
        if not clean_texts:
            return EmbeddingBatch(
                vectors=[],
                provider="local",
                model=self.model_name,
                vector_dim=0,
            )

        model = self._load_model()
        # normalize_embeddings=True 时，向量会被归一化到单位长度。
        # 后续 Qdrant 使用 cosine distance 时，归一化能让相似度更稳定。
        vectors = model.encode(
            clean_texts,
            batch_size=self.batch_size,
            normalize_embeddings=self.normalize,
            convert_to_numpy=True,
            show_progress_bar=False,
        )

        vector_list = vectors.tolist()
        vector_dim = len(vector_list[0]) if vector_list else 0
        return EmbeddingBatch(
            vectors=vector_list,
            provider="local",
            model=self.model_name,
            vector_dim=vector_dim,
        )

    def _load_model(self) -> Any:
        if self._model is not None:
            return self._model

        model_dir = Path(self.model_path).expanduser().resolve()
        if not model_dir.exists():
            raise RuntimeError(
                f"本地 embedding 模型目录不存在: {model_dir}。"
                "请先运行 scripts/download_embedding_model.py 下载模型，"
                "或通过 EMBEDDING_MODEL_PATH 指向已有模型目录。"
            )

        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as exc:
            raise RuntimeError(
                "缺少 sentence-transformers 依赖，请先在 rag-ai-service 环境中安装 requirements.txt"
            ) from exc

        # SentenceTransformer 既支持本地目录，也支持 Hugging Face / ModelScope 风格模型名。
        # 本项目推荐使用本地目录，方便未来 Docker 通过 volume 挂载模型。
        self._model = SentenceTransformer(str(model_dir), device=self.device)
        return self._model
