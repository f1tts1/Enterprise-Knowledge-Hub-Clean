from app.document_loader.base import ParsedDocument
from app.document_loader.markdown_loader import load_markdown
from app.document_loader.pdf_loader import load_pdf
from app.document_loader.text_loader import load_text
from app.document_loader.word_loader import load_word


class UnsupportedDocumentTypeError(ValueError):
    pass


def load_document(file_name: str, file_bytes: bytes) -> ParsedDocument:
    """根据上传文件扩展名分发到对应解析器。

    Java 上传时已经做过同样的扩展名白名单校验。
    Python 这里重复校验，是为了保证测试时直接调用 AI 服务、
    或未来由 worker 调用时也足够稳。
    """
    extension = _extension(file_name)

    if extension == "pdf":
        return load_pdf(file_bytes)
    if extension == "docx":
        return load_word(file_bytes)
    if extension == "txt":
        return load_text(file_bytes)
    if extension in {"md", "markdown"}:
        return load_markdown(file_bytes)

    raise UnsupportedDocumentTypeError(f"Unsupported document type: {extension or 'unknown'}")


def _extension(file_name: str) -> str:
    if "." not in file_name:
        return ""
    return file_name.rsplit(".", maxsplit=1)[-1].lower()
