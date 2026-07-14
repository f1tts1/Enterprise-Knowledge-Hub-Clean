from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TextChunk:
    """可用于 embedding 和 Qdrant payload 构造的文本块。"""

    chunk_index: int
    text: str
    page_no: int | None
    char_start: int
    char_end: int
