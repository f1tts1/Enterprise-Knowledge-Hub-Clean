from __future__ import annotations

import os
import unittest


os.environ.setdefault("MINIO_ACCESS_KEY", "unit-test-access-key")
os.environ.setdefault("MINIO_SECRET_KEY", "unit-test-secret-key")

from app.generator.service import (  # noqa: E402
    LlmProviderError,
    MODEL_REPORTED_INSUFFICIENT_CONTEXT,
    NO_ANSWER_SENTINEL,
    NO_ANSWER_TEXT,
    _parse_generated_answer,
)


class RagGenerationProtocolTest(unittest.TestCase):
    def test_exact_no_answer_sentinel_returns_structured_no_answer(self) -> None:
        answer, status, indexes, reason = _parse_generated_answer(
            NO_ANSWER_SENTINEL,
            context_count=3,
        )

        self.assertEqual(NO_ANSWER_TEXT, answer)
        self.assertEqual("INSUFFICIENT_CONTEXT", status)
        self.assertEqual([], indexes)
        self.assertEqual(MODEL_REPORTED_INSUFFICIENT_CONTEXT, reason)

    def test_answered_response_deduplicates_citations_in_first_appearance_order(self) -> None:
        answer, status, indexes, reason = _parse_generated_answer(
            "第三段说明结果 [片段 3]，第一段补充原因 [片段 1][片段 3]。",
            context_count=3,
        )

        self.assertIn("[片段 3]", answer)
        self.assertEqual("ANSWERED", status)
        self.assertEqual([3, 1], indexes)
        self.assertIsNone(reason)

    def test_response_without_citation_is_rejected(self) -> None:
        with self.assertRaises(LlmProviderError):
            _parse_generated_answer("这是一个没有引用的回答。", context_count=2)

    def test_out_of_range_citation_is_rejected(self) -> None:
        for answer in ("无效引用 [片段 0]", "越界引用 [片段 3]"):
            with self.subTest(answer=answer), self.assertRaises(LlmProviderError):
                _parse_generated_answer(answer, context_count=2)

    def test_no_answer_sentinel_cannot_be_mixed_with_other_content(self) -> None:
        for answer in (
            f"{NO_ANSWER_SENTINEL} 额外说明",
            f"{NO_ANSWER_SENTINEL} [片段 1]",
        ):
            with self.subTest(answer=answer), self.assertRaises(LlmProviderError):
                _parse_generated_answer(answer, context_count=2)


if __name__ == "__main__":
    unittest.main()
