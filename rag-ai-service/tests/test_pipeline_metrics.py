from __future__ import annotations

import os
import unittest
from unittest.mock import patch


os.environ.setdefault("MINIO_ACCESS_KEY", "unit-test-access-key")
os.environ.setdefault("MINIO_SECRET_KEY", "unit-test-secret-key")

from app.clients.qdrant_client import QdrantIndexResult, QdrantSearchResult  # noqa: E402
from app.config.settings import Settings  # noqa: E402
from app.document_loader.base import ParsedDocument, ParsedPage  # noqa: E402
from app.embedding.base import EmbeddingBatch  # noqa: E402
from app.generator.service import RagGenerationService, _extract_usage  # noqa: E402
from app.indexing.service import IndexingService  # noqa: E402
from app.observability.request_context import (  # noqa: E402
    REQUEST_ID_HEADER,
    reset_request_id,
    set_request_id,
)
from app.retrieval.service import RetrievalService  # noqa: E402
from app.schemas.document import IndexDocumentRequest  # noqa: E402
from app.schemas.rag import RagContextChunk, RagGenerateRequest  # noqa: E402
from app.schemas.retrieval import RetrievalSearchRequest  # noqa: E402
from app.splitter.base import TextChunk  # noqa: E402


class _FakeObjectReader:
    def read_object(self, bucket: str, object_key: str) -> bytes:
        return b"document bytes"


class _FakeSplitter:
    def split_document(self, parsed_document: ParsedDocument) -> list[TextChunk]:
        return [
            TextChunk(
                chunk_index=0,
                text=parsed_document.text,
                page_no=1,
                char_start=0,
                char_end=12,
            )
        ]


class _FakeEmbeddingProvider:
    def embed_texts(self, texts: list[str]) -> EmbeddingBatch:
        return EmbeddingBatch(
            vectors=[[0.1, 0.2]],
            provider="fake",
            model="fake-model",
            vector_dim=2,
        )


class _FakeVectorStore:
    collection_name = "fake-collection"

    def upsert_chunks(self, request, chunks, embedding_batch) -> QdrantIndexResult:
        return QdrantIndexResult(collection_name=self.collection_name, indexed_count=len(chunks))

    def search_chunks(self, owner_user_id, kb_id, query_vector, limit) -> list[QdrantSearchResult]:
        return [
            QdrantSearchResult(
                point_id="point-1",
                score=0.9,
                payload={
                    "docId": 3,
                    "chunkId": "3:0",
                    "fileName": "guide.txt",
                    "pageNo": 1,
                    "chunkIndex": 0,
                    "charStart": 0,
                    "charEnd": 12,
                    "text": "safe result",
                },
            )
        ]


class PipelineMetricsTest(unittest.TestCase):
    def test_indexing_returns_each_stage_latency_without_logging_object_key(self) -> None:
        service = IndexingService.__new__(IndexingService)
        service._splitter = _FakeSplitter()
        service._embedding_provider = _FakeEmbeddingProvider()
        service._vector_store = _FakeVectorStore()
        request = IndexDocumentRequest(
            task_id=1,
            document_id=3,
            kb_id=2,
            owner_user_id=1,
            file_name="guide.txt",
            content_type="text/plain",
            file_size=14,
            bucket="documents",
            object_key="private/secret-object-key",
        )
        parsed = ParsedDocument(pages=[ParsedPage(page_no=1, text="safe result")])

        with (
            patch("app.indexing.service.minio_object_reader", _FakeObjectReader()),
            patch("app.indexing.service.load_document", return_value=parsed),
            self.assertLogs("app.indexing.service", level="INFO") as logs,
        ):
            response = service.submit_indexing(request)

        self.assertEqual("INDEXED", response.status)
        self.assertEqual("fake", response.embedding_provider)
        for latency in (
            response.download_latency_ms,
            response.parse_latency_ms,
            response.split_latency_ms,
            response.embedding_latency_ms,
            response.vector_store_latency_ms,
            response.total_latency_ms,
        ):
            self.assertGreaterEqual(latency, 0)
        self.assertNotIn(request.object_key, "\n".join(logs.output))

    def test_retrieval_returns_metadata_and_latency_without_logging_query(self) -> None:
        service = RetrievalService.__new__(RetrievalService)
        service._embedding_provider = _FakeEmbeddingProvider()
        service._vector_store = _FakeVectorStore()
        request = RetrievalSearchRequest(
            owner_user_id=1,
            kb_id=2,
            query="private search question",
            top_k=5,
        )

        with self.assertLogs("app.retrieval.service", level="INFO") as logs:
            response = service.search(request)

        self.assertEqual("fake", response.embedding_provider)
        self.assertEqual("fake-model", response.embedding_model)
        self.assertEqual(1, len(response.records))
        self.assertGreaterEqual(response.embedding_latency_ms, 0)
        self.assertGreaterEqual(response.vector_store_latency_ms, 0)
        self.assertGreaterEqual(response.total_latency_ms, 0)
        self.assertNotIn(request.query, "\n".join(logs.output))


class _FakeHttpResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return {
            "choices": [{"message": {"content": "private generated answer [片段 1]"}}],
            "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18},
        }


class _FakeAsyncClient:
    def __init__(self) -> None:
        self.headers: dict[str, str] | None = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback) -> None:
        return None

    async def post(self, url: str, headers: dict[str, str], json: dict) -> _FakeHttpResponse:
        self.headers = headers
        return _FakeHttpResponse()


class RagGenerationMetricsTest(unittest.IsolatedAsyncioTestCase):
    async def test_generate_propagates_request_id_and_returns_usage_without_logging_content(self) -> None:
        settings = Settings(
            minio_access_key="unused",
            minio_secret_key="unused",
            llm_provider="openai-compatible",
            llm_base_url="https://llm.invalid/v1",
            llm_api_key="private-api-key",
            llm_model="fake-chat-model",
        )
        service = RagGenerationService(settings)
        request = RagGenerateRequest(
            question="private user question",
            contexts=[RagContextChunk(doc_id=3, text="private context")],
        )
        client = _FakeAsyncClient()
        token = set_request_id("java-request-2")
        try:
            with (
                patch("app.generator.service.httpx.AsyncClient", return_value=client),
                self.assertLogs("app.generator.service", level="INFO") as logs,
            ):
                response = await service.generate(request)
        finally:
            reset_request_id(token)

        self.assertEqual("java-request-2", client.headers[REQUEST_ID_HEADER])
        self.assertEqual("ANSWERED", response.answer_status)
        self.assertEqual([1], response.cited_context_indexes)
        self.assertIsNone(response.no_answer_reason)
        self.assertEqual(11, response.prompt_tokens)
        self.assertEqual(7, response.completion_tokens)
        self.assertEqual(18, response.total_tokens)
        self.assertGreaterEqual(response.llm_latency_ms, 0)
        output = "\n".join(logs.output)
        self.assertIn("request_id=java-request-2", output)
        self.assertNotIn(request.question, output)
        self.assertNotIn(response.answer, output)
        self.assertNotIn(settings.llm_api_key, output)

    def test_usage_can_derive_total_and_keeps_missing_usage_unknown(self) -> None:
        self.assertEqual(
            (4, 5, 9),
            _extract_usage({"usage": {"prompt_tokens": 4, "completion_tokens": 5}}),
        )
        self.assertEqual((None, None, None), _extract_usage({}))


if __name__ == "__main__":
    unittest.main()
