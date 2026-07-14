from __future__ import annotations

from io import BytesIO

from app.document_loader.base import ParsedDocument, ParsedPage


def load_pdf(file_bytes: bytes) -> ParsedDocument:
    """使用 PyMuPDF 按页提取 PDF 文本。

    页码会保留下来，因为后续 RAG 回答的引用溯源需要 page_no。
    """
    import fitz

    pages: list[ParsedPage] = []
    with fitz.open(stream=BytesIO(file_bytes), filetype="pdf") as document:
        for index, page in enumerate(document, start=1):
            pages.append(ParsedPage(page_no=index, text=page.get_text("text")))
    return ParsedDocument(pages=pages)
