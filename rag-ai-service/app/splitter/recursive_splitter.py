from __future__ import annotations

from app.document_loader.base import ParsedDocument
from app.splitter.base import TextChunk


class RecursiveTextSplitter:
    """在保留来源元数据的前提下切分解析后的文档文本。

    这里的目标不是简单“把长文本切短”。每个 chunk 都保留页码和字符偏移，
    这样后续检索结果才能转换成答案引用。递归分隔策略会优先选择段落/句子边界，
    实在切不开时才退化为固定长度切分。
    """

    def __init__(
        self,
        chunk_size: int = 800,
        chunk_overlap: int = 120,
        separators: tuple[str, ...] = ("\n\n", "\n", "。", "！", "？", "；", "，", " ", ""),
    ) -> None:
        if chunk_size <= 0:
            raise ValueError("chunk_size must be greater than 0")
        if chunk_overlap < 0:
            raise ValueError("chunk_overlap must be greater than or equal to 0")
        if chunk_overlap >= chunk_size:
            raise ValueError("chunk_overlap must be less than chunk_size")

        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self.separators = separators

    def split_document(self, document: ParsedDocument) -> list[TextChunk]:
        chunks: list[TextChunk] = []
        chunk_index = 0

        for page in document.pages:
            # 每一页独立切分，保证 page_no 对引用溯源仍然准确。
            # 跨页 chunk 后续展示给用户时会更难解释。
            text = page.text.strip()
            if not text:
                continue

            page_chunks = self._split_text(text)
            for chunk_text, char_start, char_end in page_chunks:
                chunks.append(
                    TextChunk(
                        chunk_index=chunk_index,
                        text=chunk_text,
                        page_no=page.page_no,
                        char_start=char_start,
                        char_end=char_end,
                    )
                )
                chunk_index += 1

        return chunks

    def _split_text(self, text: str) -> list[tuple[str, int, int]]:
        # 切分前压缩空白字符，提高 chunk 密度，
        # 避免因为格式差异产生大量很小的 chunk。
        normalized = " ".join(text.split())
        if not normalized:
            return []

        candidate_chunks = self._recursive_split(normalized, self.separators)
        return self._merge_chunks(candidate_chunks)

    def _recursive_split(self, text: str, separators: tuple[str, ...]) -> list[str]:
        # 按从大到小的分隔符尝试切分。中文资料中，
        # “。”、“，”等标点通常比空格更适合作为边界。
        if len(text) <= self.chunk_size:
            return [text]
        if not separators:
            return [
                text[index : index + self.chunk_size]
                for index in range(0, len(text), self.chunk_size)
            ]

        separator = separators[0]
        remaining_separators = separators[1:]

        if separator == "":
            return [
                text[index : index + self.chunk_size]
                for index in range(0, len(text), self.chunk_size)
            ]

        pieces = text.split(separator)
        if len(pieces) == 1:
            return self._recursive_split(text, remaining_separators)

        chunks: list[str] = []
        current = ""
        for piece in pieces:
            if not piece:
                continue

            candidate = piece if not current else current + separator + piece
            if len(candidate) <= self.chunk_size:
                current = candidate
                continue

            if current:
                chunks.extend(self._recursive_split(current, remaining_separators))
            current = piece

        if current:
            chunks.extend(self._recursive_split(current, remaining_separators))

        return chunks

    def _merge_chunks(self, chunks: list[str]) -> list[tuple[str, int, int]]:
        # 把小片段重新合并到接近 chunk_size，并添加 overlap。
        # 当答案横跨 chunk 边界时，overlap 能保留更多上下文。
        merged: list[tuple[str, int, int]] = []
        current = ""
        current_start = 0
        cursor = 0

        for chunk in chunks:
            if not chunk:
                continue

            separator = "" if not current else " "
            candidate = current + separator + chunk
            if current and len(candidate) > self.chunk_size:
                current_end = current_start + len(current)
                merged.append((current, current_start, current_end))

                max_overlap = max(self.chunk_size - len(chunk) - 1, 0)
                overlap_size = min(self.chunk_overlap, max_overlap)
                overlap_text = current[-overlap_size:] if overlap_size else ""
                # 如果下一个片段已经接近 chunk_size，就减少 overlap，
                # 避免生成超长 chunk。
                current = (overlap_text + " " + chunk).strip() if overlap_text else chunk
                current_start = max(current_end - len(overlap_text), 0)
            else:
                if not current:
                    current_start = cursor
                current = candidate

            cursor += len(chunk) + 1

        if current:
            merged.append((current, current_start, current_start + len(current)))

        return merged
