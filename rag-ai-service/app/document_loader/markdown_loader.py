from app.document_loader.base import ParsedDocument
from app.document_loader.text_loader import load_text


def load_markdown(file_bytes: bytes) -> ParsedDocument:
    return load_text(file_bytes)
