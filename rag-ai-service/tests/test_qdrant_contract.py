from __future__ import annotations

import os
import threading
import unittest


os.environ.setdefault("MINIO_ACCESS_KEY", "unit-test-access-key")
os.environ.setdefault("MINIO_SECRET_KEY", "unit-test-secret-key")

from app.clients.qdrant_client import QdrantChunkVectorStore  # noqa: E402
from app.embedding.base import EmbeddingBatch  # noqa: E402
from app.schemas.document import IndexDocumentRequest  # noqa: E402
from app.splitter.base import TextChunk  # noqa: E402


def _filter_values(query_filter) -> dict[str, object]:
    return {condition.key: condition.match.value for condition in query_filter.must}


class _FakeQdrantClient:
    def __init__(self) -> None:
        self.search_call: dict | None = None
        self.delete_call: dict | None = None

    def collection_exists(self, collection_name: str) -> bool:
        return True

    def search(self, **kwargs):
        self.search_call = kwargs
        return []

    def delete(self, **kwargs) -> None:
        self.delete_call = kwargs


def _vector_store(client: _FakeQdrantClient) -> QdrantChunkVectorStore:
    store = QdrantChunkVectorStore.__new__(QdrantChunkVectorStore)
    store.collection_name = "test_chunks"
    store._client = client
    store._payload_indexes_ensured = True
    store._schema_lock = threading.Lock()
    return store


class QdrantContractTest(unittest.TestCase):
    def test_search_pushes_owner_and_knowledge_base_into_filter(self) -> None:
        client = _FakeQdrantClient()
        store = _vector_store(client)

        results = store.search_chunks(owner_user_id=11, kb_id=22, query_vector=[0.1, 0.2], limit=5)

        self.assertEqual([], results)
        self.assertIsNotNone(client.search_call)
        self.assertEqual(
            {"ownerUserId": 11, "kbId": 22},
            _filter_values(client.search_call["query_filter"]),
        )
        self.assertEqual(5, client.search_call["limit"])
        self.assertTrue(client.search_call["with_payload"])

    def test_delete_uses_owner_knowledge_base_and_document_filter(self) -> None:
        client = _FakeQdrantClient()
        store = _vector_store(client)

        collection_existed = store.delete_document_points(
            owner_user_id=11,
            kb_id=22,
            document_id=33,
        )

        self.assertTrue(collection_existed)
        self.assertIsNotNone(client.delete_call)
        selector = client.delete_call["points_selector"]
        self.assertEqual(
            {"ownerUserId": 11, "kbId": 22, "docId": 33},
            _filter_values(selector.filter),
        )
        self.assertTrue(client.delete_call["wait"])

    def test_point_id_is_stable_and_payload_supports_permission_and_citation(self) -> None:
        store = _vector_store(_FakeQdrantClient())
        request = IndexDocumentRequest(
            task_id=44,
            document_id=33,
            kb_id=22,
            owner_user_id=11,
            file_name="policy.pdf",
            content_type="application/pdf",
            file_size=1024,
            bucket="documents",
            object_key="11/22/policy.pdf",
            checksum_sha256="a" * 64,
        )
        chunk = TextChunk(
            chunk_index=7,
            text="citation evidence",
            page_no=3,
            char_start=100,
            char_end=117,
        )
        embedding = EmbeddingBatch(
            vectors=[[0.1, 0.2]],
            provider="local",
            model="test-model",
            vector_dim=2,
        )

        first = store._build_point(request, chunk, embedding.vectors[0], embedding)
        second = store._build_point(request, chunk, embedding.vectors[0], embedding)
        next_chunk = TextChunk(8, "next", 3, 118, 122)
        third = store._build_point(request, next_chunk, embedding.vectors[0], embedding)

        self.assertEqual(str(first.id), str(second.id))
        self.assertNotEqual(str(first.id), str(third.id))
        self.assertEqual(
            {
                "ownerUserId": 11,
                "kbId": 22,
                "docId": 33,
                "taskId": 44,
                "chunkId": "doc-33-chunk-7",
                "pageNo": 3,
                "chunkIndex": 7,
                "charStart": 100,
                "charEnd": 117,
                "text": "citation evidence",
            },
            {key: first.payload[key] for key in (
                "ownerUserId",
                "kbId",
                "docId",
                "taskId",
                "chunkId",
                "pageNo",
                "chunkIndex",
                "charStart",
                "charEnd",
                "text",
            )},
        )


if __name__ == "__main__":
    unittest.main()
