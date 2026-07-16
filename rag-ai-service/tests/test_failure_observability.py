from __future__ import annotations

import os
import unittest
from unittest.mock import patch

import httpx


os.environ.setdefault("MINIO_ACCESS_KEY", "unit-test-access-key")
os.environ.setdefault("MINIO_SECRET_KEY", "unit-test-secret-key")

from app.clients.qdrant_client import QdrantIndexResult  # noqa: E402
from app.config.settings import Settings  # noqa: E402
from app.document_loader.base import ParsedDocument, ParsedPage  # noqa: E402
from app.embedding.base import EmbeddingBatch  # noqa: E402
from app.generator.service import LlmProviderError, RagGenerationService  # noqa: E402
from app.indexing.service import IndexingService  # noqa: E402
from app.observability.request_context import reset_request_id, set_request_id  # noqa: E402
from app.retrieval.service import RetrievalService  # noqa: E402
from app.schemas.document import IndexDocumentRequest  # noqa: E402
from app.schemas.rag import RagContextChunk, RagGenerateRequest  # noqa: E402
from app.schemas.retrieval import RetrievalSearchRequest  # noqa: E402
from app.splitter.base import TextChunk  # noqa: E402


ERROR_SECRET = "exception-message-very-secret"


class _SensitiveStageError(RuntimeError):
    pass


class _ObjectReader:
    def __init__(self, should_fail: bool) -> None:
        self._should_fail = should_fail

    def read_object(self, bucket: str, object_key: str) -> bytes:
        if self._should_fail:
            raise _SensitiveStageError(ERROR_SECRET)
        return b"document bytes"


class _Splitter:
    def __init__(self, should_fail: bool) -> None:
        self._should_fail = should_fail

    def split_document(self, parsed_document: ParsedDocument) -> list[TextChunk]:
        if self._should_fail:
            raise _SensitiveStageError(ERROR_SECRET)
        return [
            TextChunk(
                chunk_index=0,
                text=parsed_document.text,
                page_no=1,
                char_start=0,
                char_end=len(parsed_document.text),
            )
        ]


class _EmbeddingProvider:
    def __init__(self, should_fail: bool) -> None:
        self._should_fail = should_fail

    def embed_texts(self, texts: list[str]) -> EmbeddingBatch:
        if self._should_fail:
            raise _SensitiveStageError(ERROR_SECRET)
        return EmbeddingBatch(
            vectors=[[0.1, 0.2]],
            provider="fake",
            model="fake-model",
            vector_dim=2,
        )


class _IndexVectorStore:
    collection_name = "fake-collection"

    def __init__(self, should_fail: bool) -> None:
        self._should_fail = should_fail

    def upsert_chunks(self, request, chunks, embedding_batch) -> QdrantIndexResult:
        if self._should_fail:
            raise _SensitiveStageError(ERROR_SECRET)
        return QdrantIndexResult(collection_name=self.collection_name, indexed_count=len(chunks))


class _RetrievalVectorStore:
    collection_name = "fake-collection"

    def search_chunks(self, owner_user_id, kb_id, query_vector, limit):
        raise _SensitiveStageError(ERROR_SECRET)


class FailureLogSafetyTest(unittest.TestCase):
    def test_indexing_logs_each_failure_stage_without_sensitive_values(self) -> None:
        request = IndexDocumentRequest(
            task_id=11,
            document_id=12,
            kb_id=13,
            owner_user_id=14,
            file_name="private-file-name.txt",
            content_type="text/plain",
            file_size=20,
            bucket="private-bucket-name",
            object_key="private/object-key",
        )
        parsed = ParsedDocument(
            pages=[ParsedPage(page_no=1, text="private-document-content")]
        )

        for stage in ("download", "parse", "split", "embedding", "vector_store"):
            with self.subTest(stage=stage):
                service = IndexingService.__new__(IndexingService)
                service._splitter = _Splitter(stage == "split")
                service._embedding_provider = _EmbeddingProvider(stage == "embedding")
                service._vector_store = _IndexVectorStore(stage == "vector_store")
                load_result = (
                    _SensitiveStageError(ERROR_SECRET)
                    if stage == "parse"
                    else parsed
                )
                request_id_token = set_request_id("index-failure-test")
                try:
                    with (
                        patch(
                            "app.indexing.service.minio_object_reader",
                            _ObjectReader(stage == "download"),
                        ),
                        patch(
                            "app.indexing.service.load_document",
                            side_effect=load_result
                            if isinstance(load_result, Exception)
                            else None,
                            return_value=None
                            if isinstance(load_result, Exception)
                            else load_result,
                        ),
                        self.assertLogs("app.indexing.service", level="ERROR") as logs,
                        self.assertRaises(_SensitiveStageError),
                    ):
                        service.submit_indexing(request)
                finally:
                    reset_request_id(request_id_token)

                output = "\n".join(logs.output)
                self.assertIn("request_id=index-failure-test", output)
                self.assertIn(f"failure_stage={stage}", output)
                self.assertIn("error_type=_SensitiveStageError", output)
                self.assertIn("total_latency_ms=", output)
                self._assert_no_sensitive_value(
                    output,
                    request.file_name,
                    request.bucket,
                    request.object_key,
                    parsed.text,
                    ERROR_SECRET,
                )

    def test_retrieval_logs_embedding_and_vector_store_failures_without_query(self) -> None:
        request = RetrievalSearchRequest(
            owner_user_id=21,
            kb_id=22,
            query="private-query-text",
            top_k=5,
        )

        for stage in ("embedding", "vector_store"):
            with self.subTest(stage=stage):
                service = RetrievalService.__new__(RetrievalService)
                service._embedding_provider = _EmbeddingProvider(stage == "embedding")
                service._vector_store = _RetrievalVectorStore()
                request_id_token = set_request_id("retrieval-failure-test")
                try:
                    with (
                        self.assertLogs("app.retrieval.service", level="ERROR") as logs,
                        self.assertRaises(_SensitiveStageError),
                    ):
                        service.search(request)
                finally:
                    reset_request_id(request_id_token)

                output = "\n".join(logs.output)
                self.assertIn("request_id=retrieval-failure-test", output)
                self.assertIn(f"failure_stage={stage}", output)
                self.assertIn("error_type=_SensitiveStageError", output)
                self.assertIn("total_latency_ms=", output)
                self._assert_no_sensitive_value(output, request.query, ERROR_SECRET)

    @staticmethod
    def _assert_no_sensitive_value(output: str, *sensitive_values: str) -> None:
        for value in sensitive_values:
            if value:
                if value in output:
                    raise AssertionError(f"sensitive value leaked into log: {value!r}")
        if "Traceback" in output:
            raise AssertionError("failure log must not contain a stack trace")


class _HttpFailureClient:
    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback) -> None:
        return None

    async def post(self, url: str, headers: dict[str, str], json: dict):
        raise httpx.ConnectError(ERROR_SECRET)


class _ResponseParseFailure:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        raise _SensitiveStageError(ERROR_SECRET)


class _ResponseParseFailureClient:
    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback) -> None:
        return None

    async def post(self, url: str, headers: dict[str, str], json: dict):
        return _ResponseParseFailure()


class RagFailureLogSafetyTest(unittest.IsolatedAsyncioTestCase):
    def _request(self) -> RagGenerateRequest:
        return RagGenerateRequest(
            question="private-question-text",
            contexts=[RagContextChunk(doc_id=31, text="private-context-text")],
        )

    def _configured_settings(self) -> Settings:
        return Settings(
            minio_access_key="unused",
            minio_secret_key="unused",
            llm_provider="openai-compatible",
            llm_base_url="https://llm.invalid/v1",
            llm_api_key="private-api-key",
            llm_model="fake-chat-model",
        )

    async def test_configuration_failure_is_logged_without_request_content(self) -> None:
        settings = Settings(
            minio_access_key="unused",
            minio_secret_key="unused",
            llm_provider="none",
            deepseek_api_key="",
        )
        service = RagGenerationService(settings)
        request = self._request()

        request_id_token = set_request_id("rag-failure-test")
        try:
            with (
                self.assertLogs("app.generator.service", level="ERROR") as logs,
                self.assertRaises(LlmProviderError),
            ):
                await service.generate(request)
        finally:
            reset_request_id(request_id_token)

        output = "\n".join(logs.output)
        self.assertIn("request_id=rag-failure-test", output)
        self.assertIn("failure_stage=configuration", output)
        self.assertIn("error_type=LlmProviderError", output)
        self._assert_rag_log_safe(output, request, None)

    async def test_http_failure_keeps_provider_error_behavior_and_hides_error_message(self) -> None:
        settings = self._configured_settings()
        service = RagGenerationService(settings)
        request = self._request()

        request_id_token = set_request_id("rag-failure-test")
        try:
            with (
                patch(
                    "app.generator.service.httpx.AsyncClient",
                    return_value=_HttpFailureClient(),
                ),
                self.assertLogs("app.generator.service", level="ERROR") as logs,
                self.assertRaises(LlmProviderError),
            ):
                await service.generate(request)
        finally:
            reset_request_id(request_id_token)

        output = "\n".join(logs.output)
        self.assertIn("request_id=rag-failure-test", output)
        self.assertIn("failure_stage=llm_http", output)
        self.assertIn("error_type=ConnectError", output)
        self._assert_rag_log_safe(output, request, settings.llm_api_key)

    async def test_response_parse_failure_preserves_exception_type_and_hides_content(self) -> None:
        settings = self._configured_settings()
        service = RagGenerationService(settings)
        request = self._request()

        request_id_token = set_request_id("rag-failure-test")
        try:
            with (
                patch(
                    "app.generator.service.httpx.AsyncClient",
                    return_value=_ResponseParseFailureClient(),
                ),
                self.assertLogs("app.generator.service", level="ERROR") as logs,
                self.assertRaises(_SensitiveStageError),
            ):
                await service.generate(request)
        finally:
            reset_request_id(request_id_token)

        output = "\n".join(logs.output)
        self.assertIn("request_id=rag-failure-test", output)
        self.assertIn("failure_stage=response_parse", output)
        self.assertIn("error_type=_SensitiveStageError", output)
        self._assert_rag_log_safe(output, request, settings.llm_api_key)

    @staticmethod
    def _assert_rag_log_safe(
        output: str,
        request: RagGenerateRequest,
        api_key: str | None,
    ) -> None:
        sensitive_values = [
            request.question,
            *(context.text for context in request.contexts),
            api_key,
            ERROR_SECRET,
        ]
        for value in sensitive_values:
            if value and value in output:
                raise AssertionError(f"sensitive value leaked into log: {value!r}")
        if "Traceback" in output:
            raise AssertionError("failure log must not contain a stack trace")


if __name__ == "__main__":
    unittest.main()
