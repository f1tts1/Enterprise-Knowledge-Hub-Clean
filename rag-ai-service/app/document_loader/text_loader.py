from app.document_loader.base import ParsedDocument, ParsedPage


def load_text(file_bytes: bytes) -> ParsedDocument:
    """把纯文本文件解析成单页文档。"""
    text = decode_text(file_bytes)
    return ParsedDocument(pages=[ParsedPage(page_no=1, text=text)])


def decode_text(file_bytes: bytes) -> str:
    """尝试中文/英文资料里常见的文本编码。"""
    for encoding in ("utf-8", "utf-8-sig", "gb18030"):
        try:
            return file_bytes.decode(encoding)
        except UnicodeDecodeError:
            continue
    return file_bytes.decode("utf-8", errors="replace")
