#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib import error, request


DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_DATASET_DIR = Path("eval/rag")
NO_ANSWER_STATUSES = {"NO_CONTEXT", "INSUFFICIENT_CONTEXT"}


@dataclass
class ApiResult:
    status: int
    payload: dict[str, Any] | None
    text: str

    @property
    def success(self) -> bool:
        return bool(self.payload and self.payload.get("success") is True)

    @property
    def code(self) -> str | None:
        if not self.payload:
            return None
        code = self.payload.get("code")
        return None if code is None else str(code)

    @property
    def data(self) -> dict[str, Any]:
        if not self.payload:
            return {}
        data = self.payload.get("data")
        return data if isinstance(data, dict) else {}


class EvalError(RuntimeError):
    pass


def main() -> int:
    args = parse_args()
    dataset_dir = args.dataset_dir.resolve()
    config = read_json(dataset_dir / "eval_config.json")
    questions = read_jsonl(dataset_dir / "questions.jsonl")
    if args.question_type:
        questions = [q for q in questions if q.get("question_type") == args.question_type]
    if args.max_questions:
        questions = questions[: args.max_questions]
    if not questions:
        raise EvalError("没有可执行的评测问题")

    run_id = args.run_id or build_run_id()
    output_dir = (args.output_dir / run_id).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"RAG evaluation run id: {run_id}")
    print(f"Java base URL: {args.base_url}")
    print(f"Dataset: {dataset_dir}")
    print(f"Output: {output_dir}")

    state = bootstrap_corpus(args, config, dataset_dir, run_id)
    results = []
    for index, question in enumerate(questions, start=1):
        print(f"[{index}/{len(questions)}] {question['id']} {question['question_type']}: {question['question']}")
        results.append(evaluate_question(args, question, state))

    summary = summarize(results, args)
    write_json(output_dir / "run_config.json", {
        "run_id": run_id,
        "base_url": args.base_url,
        "dataset_dir": str(dataset_dir),
        "retrieval_only": args.retrieval_only,
        "top_k": args.top_k,
        "question_type": args.question_type,
        "max_questions": args.max_questions,
        "started_at": state["started_at"],
        "users": state["users_public"],
        "knowledge_bases": state["knowledge_bases"],
        "documents": state["documents_public"],
    })
    write_jsonl(output_dir / "results.jsonl", results)
    write_json(output_dir / "summary.json", summary)
    write_bad_cases(output_dir / "bad_cases.md", results, summary)

    print()
    print("Evaluation summary")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print()
    print(f"Saved results to: {output_dir}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a minimal RAG evaluation baseline against the Spring Boot Java API."
    )
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--dataset-dir", type=Path, default=DEFAULT_DATASET_DIR)
    parser.add_argument("--output-dir", type=Path, default=Path("eval/rag/results"))
    parser.add_argument("--run-id", default=None)
    parser.add_argument("--password", default="Password123")
    parser.add_argument("--top-k", type=int, default=None, help="Override dataset/default topK.")
    parser.add_argument("--wait-attempts", type=int, default=90)
    parser.add_argument("--wait-seconds", type=float, default=2.0)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--retrieval-only", action="store_true", help="Skip /rag/ask and only score retrieval.")
    parser.add_argument("--question-type", default=None)
    parser.add_argument("--max-questions", type=int, default=None)
    return parser.parse_args()


def bootstrap_corpus(
        args: argparse.Namespace,
        config: dict[str, Any],
        dataset_dir: Path,
        run_id: str,
) -> dict[str, Any]:
    users: dict[str, dict[str, Any]] = {}
    users_public: dict[str, dict[str, Any]] = {}
    knowledge_bases: dict[str, dict[str, Any]] = {}
    documents: dict[str, dict[str, Any]] = {}
    documents_public: dict[str, dict[str, Any]] = {}

    for user_config in config["users"]:
        user_key = user_config["key"]
        username = f"{user_config['username_prefix']}_{run_id}"
        email = f"{user_config['email_prefix']}_{run_id}@example.com"
        register_payload = {
            "username": username,
            "password": args.password,
            "nickname": user_config["nickname"],
            "email": email,
        }
        assert_success(post_json(args, "/api/v1/auth/register", None, register_payload), f"注册用户 {username}")
        login_result = assert_success(post_json(args, "/api/v1/auth/login", None, {
            "username": username,
            "password": args.password,
        }), f"登录用户 {username}")
        token = login_result.data["accessToken"]
        user_id = login_result.data["user"]["id"]
        users[user_key] = {"id": user_id, "username": username, "token": token}
        users_public[user_key] = {"id": user_id, "username": username}

    for kb_config in config["knowledge_bases"]:
        owner = users[kb_config["owner"]]
        result = assert_success(post_json(args, "/api/v1/knowledge-bases", owner["token"], {
            "name": f"{kb_config['name']} {run_id}",
            "description": kb_config.get("description"),
        }), f"创建知识库 {kb_config['key']}")
        knowledge_bases[kb_config["key"]] = {
            "id": result.data["id"],
            "owner": kb_config["owner"],
            "name": result.data["name"],
        }

    for doc_config in config["documents"]:
        owner = users[doc_config["owner"]]
        kb = knowledge_bases[doc_config["kb"]]
        file_path = dataset_dir / doc_config["path"]
        result = assert_success(upload_file(
            args=args,
            path=f"/api/v1/knowledge-bases/{kb['id']}/documents",
            token=owner["token"],
            file_path=file_path,
        ), f"上传文档 {doc_config['key']}")
        document_id = result.data["id"]
        documents[doc_config["key"]] = {
            "id": document_id,
            "owner": doc_config["owner"],
            "kb": doc_config["kb"],
            "file_name": result.data["fileName"],
        }
        documents_public[doc_config["key"]] = {
            "id": document_id,
            "owner": doc_config["owner"],
            "kb": doc_config["kb"],
            "file_name": result.data["fileName"],
        }

    for doc_key, doc in documents.items():
        owner = users[doc["owner"]]
        wait_index_completed(args, owner["token"], doc["id"], doc_key)

    return {
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "users": users,
        "users_public": users_public,
        "knowledge_bases": knowledge_bases,
        "documents": documents,
        "documents_public": documents_public,
        "default_top_k": config.get("default_top_k", 5),
    }


def evaluate_question(args: argparse.Namespace, question: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
    user = state["users"][question["user_key"]]
    kb = state["knowledge_bases"][question["kb_key"]]
    top_k = args.top_k or question.get("top_k") or state["default_top_k"]
    expected_doc_ids = [
        state["documents"][doc_key]["id"]
        for doc_key in question.get("expected_doc_keys", [])
    ]
    expected_doc_keys_by_id = {
        state["documents"][doc_key]["id"]: doc_key
        for doc_key in question.get("expected_doc_keys", [])
    }

    retrieval_result = post_json(
        args,
        f"/api/v1/knowledge-bases/{kb['id']}/retrieval/search",
        user["token"],
        {"query": question["question"], "topK": top_k},
    )
    expected_error_code = question.get("expected_error_code")
    if expected_error_code:
        permission_pass = retrieval_result.success is False and retrieval_result.code == expected_error_code
        rag_permission_pass = None
        rag_result = None
        if not args.retrieval_only:
            rag_result = post_json(
                args,
                f"/api/v1/knowledge-bases/{kb['id']}/rag/ask",
                user["token"],
                {"question": question["question"], "topK": top_k},
            )
            rag_permission_pass = rag_result.success is False and rag_result.code == expected_error_code
            permission_pass = permission_pass and rag_permission_pass
        return {
            "id": question["id"],
            "question_type": question["question_type"],
            "question": question["question"],
            "top_k": top_k,
            "expected_error_code": expected_error_code,
            "retrieval_http_status": retrieval_result.status,
            "retrieval_code": retrieval_result.code,
            "rag_http_status": None if rag_result is None else rag_result.status,
            "rag_code": None if rag_result is None else rag_result.code,
            "permission_pass": permission_pass,
            "bad_case_reasons": [] if permission_pass else ["permission_error_code_mismatch"],
        }

    if not retrieval_result.success:
        return {
            "id": question["id"],
            "question_type": question["question_type"],
            "question": question["question"],
            "top_k": top_k,
            "retrieval_http_status": retrieval_result.status,
            "retrieval_code": retrieval_result.code,
            "bad_case_reasons": ["retrieval_api_failed"],
        }

    records = retrieval_result.data.get("records") or []
    retrieval_text = join_texts([record.get("text") for record in records])
    retrieved_doc_ids = [record.get("docId") for record in records]
    retrieved_doc_keys = [
        doc_key_by_id(state, doc_id)
        for doc_id in retrieved_doc_ids
    ]
    first_rank = first_relevant_rank(records, expected_doc_ids)
    recall_hit = first_rank is not None if expected_doc_ids else None
    reciprocal_rank = 0.0 if first_rank is None else 1.0 / first_rank
    evidence_hit = contains_all(retrieval_text, question.get("evidence_terms") or []) if expected_doc_ids else None
    forbidden_leak_retrieval = contains_any(retrieval_text, question.get("forbidden_terms") or [])

    rag_payload = None
    answer = None
    answer_status = None
    no_answer = None
    no_answer_reason = None
    citations = []
    answer_correct = None
    citation_correct = None
    no_answer_pass = None
    answer_question_echo = False
    forbidden_leak_rag_chunks = False
    forbidden_leak_citations = False
    forbidden_leak_answer_source = False
    forbidden_leak_rag = False
    rag_api_failed = False
    if not args.retrieval_only:
        rag_result = post_json(
            args,
            f"/api/v1/knowledge-bases/{kb['id']}/rag/ask",
            user["token"],
            {"question": question["question"], "topK": top_k},
        )
        if rag_result.success:
            rag_payload = rag_result.data
            answer = str(rag_payload.get("answer") or "")
            answer_status = rag_payload.get("answerStatus")
            no_answer = rag_payload.get("noAnswer")
            no_answer_reason = rag_payload.get("noAnswerReason")
            citations = rag_payload.get("citations") or []
            rag_chunks_text = join_texts(
                [chunk.get("text") for chunk in rag_payload.get("retrievedChunks") or []]
            )
            citation_text = join_texts([citation.get("text") for citation in citations])
            forbidden_terms = question.get("forbidden_terms") or []
            forbidden_leak_rag_chunks = contains_any(rag_chunks_text, forbidden_terms)
            forbidden_leak_citations = contains_any(citation_text, forbidden_terms)
            answer_forbidden_terms = matching_terms(answer, forbidden_terms)
            source_text = join_texts([rag_chunks_text, citation_text])
            question_text = str(question.get("question") or "")
            answer_question_echo = any(
                term in question_text and term not in source_text
                for term in answer_forbidden_terms
            )
            forbidden_leak_answer_source = any(
                term not in question_text or term in source_text
                for term in answer_forbidden_terms
            )
            forbidden_leak_rag = any((
                forbidden_leak_rag_chunks,
                forbidden_leak_citations,
                forbidden_leak_answer_source,
            ))
            answer_terms = question.get("answer_terms") or []
            if question.get("should_answer", True) and answer_terms:
                answer_correct = (
                    answer_status == "ANSWERED"
                    and no_answer is False
                    and contains_all(answer, answer_terms)
                )
            if question.get("should_answer", True) and expected_doc_ids:
                citation_correct = (
                    answer_status == "ANSWERED"
                    and any(citation.get("docId") in expected_doc_ids for citation in citations)
                )
            if not question.get("should_answer", True):
                no_answer_pass = (
                    answer_status in NO_ANSWER_STATUSES
                    and no_answer is True
                    and bool(no_answer_reason)
                    and not citations
                    and not answer_question_echo
                    and not forbidden_leak_rag
                )
        else:
            rag_api_failed = True
            if question.get("should_answer", True) and question.get("answer_terms"):
                answer_correct = False
            if question.get("should_answer", True) and expected_doc_ids:
                citation_correct = False
            if not question.get("should_answer", True):
                no_answer_pass = False
            rag_payload = {
                "http_status": rag_result.status,
                "code": rag_result.code,
                "message": None if not rag_result.payload else rag_result.payload.get("message"),
            }

    permission_pass = not (forbidden_leak_retrieval or forbidden_leak_rag)
    bad_case_reasons = []
    if recall_hit is False:
        bad_case_reasons.append("retrieval_miss_expected_doc")
    if evidence_hit is False:
        bad_case_reasons.append("retrieval_missing_evidence_terms")
    if forbidden_leak_retrieval:
        bad_case_reasons.append("retrieval_forbidden_term_leak")
    if forbidden_leak_rag:
        bad_case_reasons.append("rag_forbidden_term_leak")
    if rag_api_failed:
        bad_case_reasons.append("rag_api_failed")
    if answer_question_echo and not question.get("should_answer", True):
        bad_case_reasons.append("no_answer_question_echo")
    if answer_correct is False:
        bad_case_reasons.append("answer_terms_missing")
    if citation_correct is False:
        bad_case_reasons.append("citation_missing_expected_doc")
    if no_answer_pass is False:
        bad_case_reasons.append("no_answer_not_recognized")
    if not permission_pass:
        bad_case_reasons.append("permission_leak")

    return {
        "id": question["id"],
        "question_type": question["question_type"],
        "question": question["question"],
        "top_k": top_k,
        "should_answer": question.get("should_answer", True),
        "expected_doc_keys": question.get("expected_doc_keys", []),
        "expected_doc_ids": expected_doc_ids,
        "retrieved_doc_ids": retrieved_doc_ids,
        "retrieved_doc_keys": retrieved_doc_keys,
        "first_relevant_rank": first_rank,
        "reciprocal_rank": reciprocal_rank,
        "recall_hit": recall_hit,
        "evidence_hit": evidence_hit,
        "forbidden_leak_retrieval": forbidden_leak_retrieval,
        "answer": answer,
        "answer_status": answer_status,
        "no_answer": no_answer,
        "no_answer_reason": no_answer_reason,
        "answer_correct": answer_correct,
        "citation_correct": citation_correct,
        "no_answer_pass": no_answer_pass,
        "answer_question_echo": answer_question_echo,
        "forbidden_leak_rag_chunks": forbidden_leak_rag_chunks,
        "forbidden_leak_citations": forbidden_leak_citations,
        "forbidden_leak_answer_source": forbidden_leak_answer_source,
        "forbidden_leak_rag": forbidden_leak_rag,
        "rag_api_failed": rag_api_failed,
        "permission_pass": permission_pass,
        "retrieval_records_preview": [
            {
                "rank": index + 1,
                "docId": record.get("docId"),
                "docKey": doc_key_by_id(state, record.get("docId")),
                "chunkId": record.get("chunkId"),
                "chunkIndex": record.get("chunkIndex"),
                "score": record.get("score"),
                "textPreview": preview(record.get("text") or ""),
            }
            for index, record in enumerate(records)
        ],
        "citations_preview": [
            {
                "index": citation.get("index"),
                "docId": citation.get("docId"),
                "docKey": doc_key_by_id(state, citation.get("docId")),
                "chunkId": citation.get("chunkId"),
                "score": citation.get("score"),
                "textPreview": preview(citation.get("text") or ""),
            }
            for citation in citations
        ],
        "rag_payload_status": rag_payload,
        "bad_case_reasons": dedupe(bad_case_reasons),
    }


def summarize(results: list[dict[str, Any]], args: argparse.Namespace) -> dict[str, Any]:
    retrieval_scored = [r for r in results if r.get("recall_hit") is not None]
    evidence_scored = [r for r in results if r.get("evidence_hit") is not None]
    answer_scored = [r for r in results if r.get("answer_correct") is not None]
    citation_scored = [r for r in results if r.get("citation_correct") is not None]
    no_answer_scored = [r for r in results if r.get("no_answer_pass") is not None]
    permission_scored = [
        r for r in results
        if r.get("question_type") == "permission" or r.get("permission_pass") is not None
    ]
    bad_cases = [r for r in results if r.get("bad_case_reasons")]

    return {
        "question_count": len(results),
        "retrieval_only": args.retrieval_only,
        "retrieval": {
            "scored_count": len(retrieval_scored),
            "recall_at_k": ratio(sum(1 for r in retrieval_scored if r.get("recall_hit")), len(retrieval_scored)),
            "mrr": average([float(r.get("reciprocal_rank") or 0.0) for r in retrieval_scored]),
            "evidence_hit_rate": ratio(sum(1 for r in evidence_scored if r.get("evidence_hit")), len(evidence_scored)),
        },
        "generation": {
            "answer_scored_count": len(answer_scored),
            "answer_correct_rate": ratio(sum(1 for r in answer_scored if r.get("answer_correct")), len(answer_scored)),
            "citation_scored_count": len(citation_scored),
            "citation_correct_rate": ratio(sum(1 for r in citation_scored if r.get("citation_correct")), len(citation_scored)),
            "no_answer_scored_count": len(no_answer_scored),
            "no_answer_pass_rate": ratio(sum(1 for r in no_answer_scored if r.get("no_answer_pass")), len(no_answer_scored)),
        },
        "permission": {
            "scored_count": len(permission_scored),
            "pass_rate": ratio(sum(1 for r in permission_scored if r.get("permission_pass")), len(permission_scored)),
            "forbidden_leak_count": sum(
                1 for r in results
                if r.get("forbidden_leak_retrieval") or r.get("forbidden_leak_rag")
            ),
            "question_echo_count": sum(
                1 for r in results if r.get("answer_question_echo")
            ),
            "retrieval_leak_count": sum(
                1 for r in results if r.get("forbidden_leak_retrieval")
            ),
            "rag_chunk_leak_count": sum(
                1 for r in results if r.get("forbidden_leak_rag_chunks")
            ),
            "citation_leak_count": sum(
                1 for r in results if r.get("forbidden_leak_citations")
            ),
            "answer_source_leak_count": sum(
                1 for r in results if r.get("forbidden_leak_answer_source")
            ),
        },
        "bad_case_count": len(bad_cases),
        "bad_case_ids": [r["id"] for r in bad_cases],
        "by_question_type": summarize_by_type(results),
    }


def summarize_by_type(results: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for result in results:
        grouped.setdefault(result.get("question_type", "unknown"), []).append(result)
    summary = {}
    for question_type, items in grouped.items():
        retrieval_items = [r for r in items if r.get("recall_hit") is not None]
        summary[question_type] = {
            "count": len(items),
            "recall_at_k": ratio(sum(1 for r in retrieval_items if r.get("recall_hit")), len(retrieval_items)),
            "bad_case_count": sum(1 for r in items if r.get("bad_case_reasons")),
        }
    return summary


def wait_index_completed(args: argparse.Namespace, token: str, document_id: int, doc_key: str) -> None:
    for attempt in range(1, args.wait_attempts + 1):
        result = get_json(args, f"/api/v1/documents/{document_id}/index-status", token)
        if not result.success:
            raise EvalError(f"查询索引状态失败 doc={doc_key}: {result.text}")
        doc_status = result.data.get("documentIndexStatus")
        task_status = result.data.get("taskStatus")
        print(f"  wait index {doc_key}: document={doc_status} task={task_status} attempt={attempt}")
        if doc_status == "INDEXED" and task_status == "SUCCESS":
            return
        if doc_status == "INDEX_FAILED" or task_status == "FAILED":
            raise EvalError(f"文档索引失败 doc={doc_key}: {json.dumps(result.payload, ensure_ascii=False)}")
        time.sleep(args.wait_seconds)
    raise EvalError(f"等待索引超时 doc={doc_key} documentId={document_id}")


def post_json(args: argparse.Namespace, path: str, token: str | None, payload: dict[str, Any]) -> ApiResult:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    return send_request(args, "POST", path, token, body, "application/json")


def get_json(args: argparse.Namespace, path: str, token: str | None) -> ApiResult:
    return send_request(args, "GET", path, token, None, None)


def upload_file(args: argparse.Namespace, path: str, token: str, file_path: Path) -> ApiResult:
    if not file_path.exists():
        raise EvalError(f"评测文档不存在: {file_path}")
    boundary = f"----ekb-rag-eval-{uuid.uuid4().hex}"
    content_type = mimetypes.guess_type(file_path.name)[0] or "text/plain"
    file_bytes = file_path.read_bytes()
    header = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    footer = f"\r\n--{boundary}--\r\n".encode("utf-8")
    body = header + file_bytes + footer
    return send_request(args, "POST", path, token, body, f"multipart/form-data; boundary={boundary}")


def send_request(
        args: argparse.Namespace,
        method: str,
        path: str,
        token: str | None,
        body: bytes | None,
        content_type: str | None,
) -> ApiResult:
    url = args.base_url.rstrip("/") + path
    headers = {"Accept": "application/json"}
    if content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = request.Request(url, data=body, headers=headers, method=method)
    try:
        with request.urlopen(req, timeout=args.timeout_seconds) as response:
            text = response.read().decode("utf-8")
            return ApiResult(response.status, parse_json_or_none(text), text)
    except error.HTTPError as exc:
        text = exc.read().decode("utf-8", errors="replace")
        return ApiResult(exc.code, parse_json_or_none(text), text)
    except error.URLError as exc:
        raise EvalError(f"无法连接 Java 后端 {args.base_url}: {exc}") from exc


def assert_success(result: ApiResult, label: str) -> ApiResult:
    if not result.success:
        raise EvalError(f"{label} 失败: http={result.status} body={result.text}")
    return result


def read_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open("r", encoding="utf-8") as file:
        for line_no, line in enumerate(file, start=1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            try:
                rows.append(json.loads(stripped))
            except json.JSONDecodeError as exc:
                raise EvalError(f"{path}:{line_no} 不是合法 JSONL: {exc}") from exc
    return rows


def write_json(path: Path, obj: Any) -> None:
    with path.open("w", encoding="utf-8") as file:
        json.dump(obj, file, ensure_ascii=False, indent=2)
        file.write("\n")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False))
            file.write("\n")


def write_bad_cases(path: Path, results: list[dict[str, Any]], summary: dict[str, Any]) -> None:
    bad_cases = [result for result in results if result.get("bad_case_reasons")]
    with path.open("w", encoding="utf-8") as file:
        file.write("# RAG Bad Cases\n\n")
        file.write("## Summary\n\n")
        file.write("```json\n")
        file.write(json.dumps(summary, ensure_ascii=False, indent=2))
        file.write("\n```\n\n")
        if not bad_cases:
            file.write("本次评测没有发现 bad case。\n")
            return
        for result in bad_cases:
            file.write(f"## {result['id']} - {result.get('question_type')}\n\n")
            file.write(f"- Question: {result.get('question')}\n")
            file.write(f"- Reasons: {', '.join(result.get('bad_case_reasons') or [])}\n")
            file.write(f"- Expected docs: {result.get('expected_doc_keys')}\n")
            file.write(f"- Retrieved docs: {result.get('retrieved_doc_keys')}\n")
            if result.get("answer"):
                file.write(f"- Answer: {result.get('answer')}\n")
            file.write("\n")


def parse_json_or_none(text: str) -> dict[str, Any] | None:
    if not text.strip():
        return None
    try:
        obj = json.loads(text)
    except json.JSONDecodeError:
        return None
    return obj if isinstance(obj, dict) else None


def first_relevant_rank(records: list[dict[str, Any]], expected_doc_ids: list[int]) -> int | None:
    if not expected_doc_ids:
        return None
    expected = set(expected_doc_ids)
    for index, record in enumerate(records, start=1):
        if record.get("docId") in expected:
            return index
    return None


def doc_key_by_id(state: dict[str, Any], doc_id: Any) -> str | None:
    for doc_key, document in state["documents"].items():
        if document["id"] == doc_id:
            return doc_key
    return None


def join_texts(values: list[Any]) -> str:
    return "\n".join(str(value) for value in values if value)


def contains_all(text: str, terms: list[str]) -> bool:
    return all(term in text for term in terms)


def contains_any(text: str, terms: list[str] | tuple[str, ...]) -> bool:
    return any(term in text for term in terms)


def matching_terms(text: str, terms: list[str]) -> list[str]:
    return [term for term in terms if term in text]


def preview(text: str, limit: int = 180) -> str:
    compact = " ".join(text.split())
    return compact[:limit]


def ratio(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return round(numerator / denominator, 4)


def average(values: list[float]) -> float | None:
    if not values:
        return None
    return round(sum(values) / len(values), 4)


def dedupe(values: list[str]) -> list[str]:
    seen = set()
    result = []
    for value in values:
        if value not in seen:
            result.append(value)
            seen.add(value)
    return result


def build_run_id() -> str:
    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
    return f"{timestamp}_{os.getpid()}_{uuid.uuid4().hex[:6]}"


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except EvalError as exc:
        print(f"RAG evaluation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
