from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ParsedPage:
    """从一个逻辑页或逻辑段落中解析出的文本。

    对 DOCX 这类没有稳定页码的格式，page_no 为 None。
    对 PDF 会保留页码，后续答案引用可以回到原始页。
    """

    page_no: int | None
    text: str


@dataclass(frozen=True)
class ParsedDocument:
    """所有文档解析器统一返回的标准结构。"""

    pages: list[ParsedPage]

    @property
    def text(self) -> str:
        # 合并文本时保留页之间的分隔。切分仍按页执行，
        # 这样不会丢失 citation 需要的页码元数据。
        return "\n\n".join(page.text for page in self.pages if page.text.strip())

    @property
    def page_count(self) -> int:
        return len(self.pages)

    @property
    def char_count(self) -> int:
        return len(self.text)
