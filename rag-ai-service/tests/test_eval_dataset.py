from __future__ import annotations

import json
import unittest
from collections import Counter
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
EVAL_ROOT = REPOSITORY_ROOT / "eval" / "rag"
CONFIG_PATH = EVAL_ROOT / "eval_config.json"
QUESTIONS_PATH = EVAL_ROOT / "questions.jsonl"


class RagEvaluationDatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        cls.questions = [
            json.loads(line)
            for line in QUESTIONS_PATH.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def test_config_references_owned_non_empty_documents(self) -> None:
        user_keys = [user["key"] for user in self.config["users"]]
        knowledge_bases = {item["key"]: item for item in self.config["knowledge_bases"]}
        document_keys = [document["key"] for document in self.config["documents"]]

        self.assertEqual(len(user_keys), len(set(user_keys)))
        self.assertEqual(len(knowledge_bases), len(self.config["knowledge_bases"]))
        self.assertEqual(len(document_keys), len(set(document_keys)))
        self.assertEqual(10, len(document_keys))
        self.assertGreater(self.config["default_top_k"], 0)

        for knowledge_base in knowledge_bases.values():
            self.assertIn(knowledge_base["owner"], user_keys)

        for document in self.config["documents"]:
            with self.subTest(document=document["key"]):
                self.assertIn(document["owner"], user_keys)
                self.assertIn(document["kb"], knowledge_bases)
                self.assertEqual(document["owner"], knowledge_bases[document["kb"]]["owner"])
                relative_path = Path(document["path"])
                self.assertFalse(relative_path.is_absolute())
                self.assertNotIn("..", relative_path.parts)
                source_path = EVAL_ROOT / relative_path
                self.assertTrue(source_path.is_file())
                self.assertTrue(source_path.read_text(encoding="utf-8").strip())

    def test_question_ids_types_and_references_are_complete(self) -> None:
        user_keys = {user["key"] for user in self.config["users"]}
        knowledge_base_keys = {item["key"] for item in self.config["knowledge_bases"]}
        document_keys = {document["key"] for document in self.config["documents"]}
        question_ids = [question["id"] for question in self.questions]

        self.assertEqual([f"Q{index:03d}" for index in range(1, 51)], question_ids)
        self.assertEqual(len(question_ids), len(set(question_ids)))
        self.assertEqual(
            {
                "single_fact": 24,
                "multi_hop": 8,
                "paraphrase": 8,
                "no_answer": 4,
                "permission": 6,
            },
            dict(Counter(question["question_type"] for question in self.questions)),
        )

        required_fields = {
            "id",
            "question_type",
            "user_key",
            "kb_key",
            "question",
            "expected_answer",
            "expected_doc_keys",
            "expected_chunk_indexes",
            "evidence_terms",
            "answer_terms",
            "forbidden_terms",
            "should_answer",
        }
        for question in self.questions:
            with self.subTest(question=question["id"]):
                self.assertTrue(required_fields.issubset(question))
                self.assertIn(question["user_key"], user_keys)
                self.assertIn(question["kb_key"], knowledge_base_keys)
                self.assertTrue(set(question["expected_doc_keys"]).issubset(document_keys))
                self.assertTrue(question["question"].strip())
                self.assertTrue(question["expected_answer"].strip())
                if "expected_error_code" in question:
                    self.assertEqual("permission", question["question_type"])
                    self.assertEqual("404001", question["expected_error_code"])

    def test_dataset_uses_current_rabbitmq_task_semantics(self) -> None:
        dataset_text = QUESTIONS_PATH.read_text(encoding="utf-8") + "\n" + "\n".join(
            (EVAL_ROOT / document["path"]).read_text(encoding="utf-8")
            for document in self.config["documents"]
        )

        for stale_term in ("Redis Stream", "XADD", "ekb:indexing:tasks"):
            with self.subTest(stale_term=stale_term):
                self.assertNotIn(stale_term, dataset_text)
        for current_term in (
            "RabbitMQ",
            "ekb.indexing.tasks",
            "documentId",
            "indexingTaskId",
            "MANUAL_RETRY",
            "current_indexing_task_id",
        ):
            with self.subTest(current_term=current_term):
                self.assertIn(current_term, dataset_text)


if __name__ == "__main__":
    unittest.main()
