from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.schemas.rag import RagGenerateRequest, RagGenerateResponse


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = REPOSITORY_ROOT / "contracts" / "ai"


class AiContractFixtureTest(unittest.TestCase):
    def test_request_fixture_matches_python_schema(self) -> None:
        payload = json.loads(
            (CONTRACT_ROOT / "rag-generate-request.json").read_text(encoding="utf-8")
        )

        request = RagGenerateRequest.model_validate(payload)

        self.assertEqual(payload, request.model_dump(mode="json"))
        self.assertEqual(33, request.contexts[0].doc_id)

    def test_response_fixture_matches_python_schema(self) -> None:
        payload = json.loads(
            (CONTRACT_ROOT / "rag-generate-response.json").read_text(encoding="utf-8")
        )

        response = RagGenerateResponse.model_validate(payload)

        self.assertEqual(payload, response.model_dump(mode="json"))
        self.assertEqual([1], response.cited_context_indexes)


if __name__ == "__main__":
    unittest.main()
