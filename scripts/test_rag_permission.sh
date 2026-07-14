#!/usr/bin/env bash
set -euo pipefail

# 验证 RAG 问答入口仍然遵守 Java 层知识库权限边界。
#
# 前置条件：
# - MySQL、Redis、MinIO、Qdrant 已启动。
# - FastAPI AI 服务已启动，并且其进程能读取 DEEPSEEK_API_KEY 或显式 LLM_* 配置。
# - Spring Boot 后端已启动。
#
# 这个脚本只验证已实现的同步 RAG：
# - Alice/Bob 各自上传并索引自己的文档。
# - Alice/Bob 只能基于自己的知识库问答。
# - Alice/Bob 直接拿对方 kbId 调 RAG 会被 Java 拦截。
# - Alice 在自己的知识库里问 Bob 私有关键词时，返回 chunk/citation 不得包含 Bob 文档。

BASE_URL="${BASE_URL:-http://localhost:8080}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC_DIR="$ROOT_DIR/tmp/retrieval-permission-test"
RUN_ID="$(date +%Y%m%d%H%M%S)_$$"

ALICE_USERNAME="alice_rag_perm_${RUN_ID}"
BOB_USERNAME="bob_rag_perm_${RUN_ID}"
PASSWORD="Password123"

require_file() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo "缺少测试文档: $file" >&2
    exit 1
  fi
}

json_get() {
  local path="$1"
  python3 -c '
import json
import sys

path = sys.argv[1]
raw = sys.stdin.read()

try:
    obj = json.loads(raw)
except json.JSONDecodeError as exc:
    print(f"响应不是合法 JSON，无法读取字段 {path}: {exc}", file=sys.stderr)
    print(raw, file=sys.stderr)
    sys.exit(1)

try:
    for part in path.split("."):
        obj = obj[part]
except (KeyError, TypeError) as exc:
    print(f"响应 JSON 缺少字段 {path}: {exc}", file=sys.stderr)
    print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)

print(obj)
' "$path"
}

post_json() {
  local url="$1"
  local token="$2"
  local payload="$3"

  if [[ -n "$token" ]]; then
    curl -sS -X POST "$url" \
      -H "Authorization: Bearer $token" \
      -H "Content-Type: application/json" \
      -d "$payload"
  else
    curl -sS -X POST "$url" \
      -H "Content-Type: application/json" \
      -d "$payload"
  fi
}

assert_success_response() {
  local response="$1"
  local label="$2"

  printf '%s' "$response" | python3 -c '
import json
import sys

label = sys.argv[1]
raw = sys.stdin.read()

try:
    obj = json.loads(raw)
except json.JSONDecodeError as exc:
    print(f"{label} 应该返回 Java 统一 JSON 响应，但实际不是合法 JSON: {exc}", file=sys.stderr)
    print(raw, file=sys.stderr)
    sys.exit(1)

if obj.get("success") is not True:
    print(f"{label} 应该成功，但实际失败了:", file=sys.stderr)
    print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$label"
}

assert_failed_code() {
  local response="$1"
  local label="$2"
  local expected_code="$3"

  printf '%s' "$response" | python3 -c '
import json
import sys

label = sys.argv[1]
expected_code = sys.argv[2]
obj = json.load(sys.stdin)
success = obj.get("success")
code = obj.get("code")
errors = []

if success is not False:
    errors.append("success 应为 false")
if code != expected_code:
    errors.append(f"code 应为 {expected_code}，实际为 {code}")

if errors:
    print(f"{label} 校验失败: " + "；".join(errors), file=sys.stderr)
    print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$label" "$expected_code"
}

assert_rag_chunks_contain() {
  local response="$1"
  local expected="$2"

  printf '%s' "$response" | python3 -c '
import json
import sys

expected = sys.argv[1]
obj = json.load(sys.stdin)
data = obj.get("data") or {}
answer = data.get("answer") or ""
citations = data.get("citations") or []
chunks = data.get("retrievedChunks") or []
joined_chunks = "\n".join((chunk.get("text") or "") for chunk in chunks)
errors = []

if not answer.strip():
    errors.append("answer 为空")
if not citations:
    errors.append("citations 为空")
if not chunks:
    errors.append("retrievedChunks 为空")
if citations and citations[0].get("score") is None:
    errors.append("citations[0].score 为空")
if expected not in joined_chunks:
    errors.append("retrievedChunks 未包含期望内容")

if errors:
    print("RAG 响应校验失败: " + "；".join(errors), file=sys.stderr)
    print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$expected"
}

assert_rag_chunks_exclude_doc_ids() {
  local response="$1"
  shift

  printf '%s' "$response" | python3 -c '
import json
import sys

forbidden_doc_ids = {int(doc_id) for doc_id in sys.argv[1:]}
obj = json.load(sys.stdin)
data = obj.get("data") or {}
chunks = data.get("retrievedChunks") or []
citations = data.get("citations") or []
bad_chunks = [chunk for chunk in chunks if chunk.get("docId") in forbidden_doc_ids]
bad_citations = [citation for citation in citations if citation.get("docId") in forbidden_doc_ids]

if bad_chunks or bad_citations:
    print("RAG 结果中出现了其它用户文档 docId", file=sys.stderr)
    print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$@"
}

assert_rag_chunks_not_contain_any() {
  local response="$1"
  shift

  printf '%s' "$response" | python3 -c '
import json
import sys

forbidden_terms = sys.argv[1:]
obj = json.load(sys.stdin)
data = obj.get("data") or {}
chunks = data.get("retrievedChunks") or []
citations = data.get("citations") or []
joined = "\n".join((item.get("text") or "") for item in chunks + citations)

for term in forbidden_terms:
    if term in joined:
        print("RAG chunk/citation 中出现了不应出现的其它用户内容:", term, file=sys.stderr)
        print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
' "$@"
}

register_user() {
  local username="$1"
  local nickname="$2"
  local email="$3"
  local response

  echo "注册用户: $username" >&2
  response="$(post_json "$BASE_URL/api/v1/auth/register" "" \
    "{\"username\":\"$username\",\"password\":\"$PASSWORD\",\"nickname\":\"$nickname\",\"email\":\"$email\"}")"
  assert_success_response "$response" "注册用户 $username"
}

login_user() {
  local username="$1"
  local response

  response="$(post_json "$BASE_URL/api/v1/auth/login" "" \
    "{\"username\":\"$username\",\"password\":\"$PASSWORD\"}")"
  assert_success_response "$response" "登录用户 $username"
  printf '%s' "$response" | json_get "data.accessToken"
}

create_kb() {
  local token="$1"
  local name="$2"
  local response

  response="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$token" \
    "{\"name\":\"$name\",\"description\":\"rag permission verification\"}")"
  assert_success_response "$response" "创建知识库 $name"
  printf '%s' "$response" | json_get "data.id"
}

upload_doc() {
  local token="$1"
  local kb_id="$2"
  local file="$3"
  local response

  echo "上传文档: $file -> kbId=$kb_id" >&2
  response="$(curl -sS -X POST "$BASE_URL/api/v1/knowledge-bases/$kb_id/documents" \
    -H "Authorization: Bearer $token" \
    -F "file=@$file;type=text/plain")"
  assert_success_response "$response" "上传文档 $file"
  printf '%s' "$response" | json_get "data.id"
}

wait_index_completed() {
  local token="$1"
  local doc_id="$2"
  local attempts="${3:-90}"

  for ((i = 1; i <= attempts; i++)); do
    local response
    response="$(curl -sS "$BASE_URL/api/v1/documents/$doc_id/index-status" \
      -H "Authorization: Bearer $token")"
    assert_success_response "$response" "查询索引状态 docId=$doc_id"

    local doc_status
    local task_status
    doc_status="$(printf '%s' "$response" | json_get "data.documentIndexStatus")"
    task_status="$(printf '%s' "$response" | json_get "data.taskStatus")"

    echo "等待索引 docId=$doc_id: document=$doc_status task=$task_status"

    if [[ "$doc_status" == "INDEXED" && "$task_status" == "SUCCESS" ]]; then
      return 0
    fi

    if [[ "$doc_status" == "INDEX_FAILED" || "$task_status" == "FAILED" ]]; then
      echo "索引失败，响应如下:" >&2
      echo "$response" >&2
      exit 1
    fi

    sleep 2
  done

  echo "等待索引超时: docId=$doc_id" >&2
  exit 1
}

rag_ask() {
  local token="$1"
  local kb_id="$2"
  local question="$3"

  post_json "$BASE_URL/api/v1/knowledge-bases/$kb_id/rag/ask" "$token" \
    "{\"question\":\"$question\",\"topK\":5}"
}

if [[ -z "${DEEPSEEK_API_KEY:-}" && -z "${LLM_API_KEY:-}" ]]; then
  echo "提示：当前 shell 未检测到 DEEPSEEK_API_KEY 或 LLM_API_KEY。"
  echo "脚本仍会继续执行；只要已启动的 FastAPI 进程能读取 LLM 配置即可。"
fi

require_file "$DOC_DIR/alice-rabbitmq-indexing.txt"
require_file "$DOC_DIR/bob-private-finance.txt"
require_file "$DOC_DIR/bob-hr-policy.txt"

echo "Java 后端地址: $BASE_URL"
echo "本次测试用户后缀: $RUN_ID"

echo
echo "==> 1. 注册 Alice/Bob 并创建各自知识库"
register_user "$ALICE_USERNAME" "Alice RAG Permission" "$ALICE_USERNAME@example.com"
register_user "$BOB_USERNAME" "Bob RAG Permission" "$BOB_USERNAME@example.com"

ALICE_TOKEN="$(login_user "$ALICE_USERNAME")"
BOB_TOKEN="$(login_user "$BOB_USERNAME")"

ALICE_KB_ID="$(create_kb "$ALICE_TOKEN" "Alice RAG Permission KB $RUN_ID")"
BOB_KB_ID="$(create_kb "$BOB_TOKEN" "Bob RAG Permission KB $RUN_ID")"

echo "Alice kbId=$ALICE_KB_ID"
echo "Bob kbId=$BOB_KB_ID"

echo
echo "==> 2. 上传并索引 Alice/Bob 私有文档"
ALICE_DOC_1="$(upload_doc "$ALICE_TOKEN" "$ALICE_KB_ID" "$DOC_DIR/alice-rabbitmq-indexing.txt")"
BOB_DOC_1="$(upload_doc "$BOB_TOKEN" "$BOB_KB_ID" "$DOC_DIR/bob-private-finance.txt")"
BOB_DOC_2="$(upload_doc "$BOB_TOKEN" "$BOB_KB_ID" "$DOC_DIR/bob-hr-policy.txt")"

wait_index_completed "$ALICE_TOKEN" "$ALICE_DOC_1"
wait_index_completed "$BOB_TOKEN" "$BOB_DOC_1"
wait_index_completed "$BOB_TOKEN" "$BOB_DOC_2"

echo
echo "==> 3. 验证 Alice RAG 只能使用 Alice 自己知识库内容"
ALICE_RAG_RESPONSE="$(rag_ask "$ALICE_TOKEN" "$ALICE_KB_ID" "RabbitMQ 在当前项目中承担什么职责？")"
assert_success_response "$ALICE_RAG_RESPONSE" "Alice RAG 问答"
assert_rag_chunks_contain "$ALICE_RAG_RESPONSE" "RabbitMQ 用于文档上传后的异步索引任务队列"
assert_rag_chunks_exclude_doc_ids "$ALICE_RAG_RESPONSE" "$BOB_DOC_1" "$BOB_DOC_2"
assert_rag_chunks_not_contain_any "$ALICE_RAG_RESPONSE" "Bob 用户" "季度现金流预测" "调薪窗口"

echo
echo "==> 4. 验证 Bob RAG 只能使用 Bob 自己知识库内容"
BOB_RAG_RESPONSE="$(rag_ask "$BOB_TOKEN" "$BOB_KB_ID" "Bob 的季度现金流预测文档包含什么内容？")"
assert_success_response "$BOB_RAG_RESPONSE" "Bob RAG 问答"
assert_rag_chunks_contain "$BOB_RAG_RESPONSE" "季度现金流预测"
assert_rag_chunks_exclude_doc_ids "$BOB_RAG_RESPONSE" "$ALICE_DOC_1"

echo
echo "==> 5. 验证 Alice 在自己知识库中问 Bob 关键词也不会召回 Bob 文档"
ALICE_QUERY_BOB_RESPONSE="$(rag_ask "$ALICE_TOKEN" "$ALICE_KB_ID" "季度现金流预测和调薪窗口是什么？")"
assert_success_response "$ALICE_QUERY_BOB_RESPONSE" "Alice 查询 Bob 关键词"
assert_rag_chunks_exclude_doc_ids "$ALICE_QUERY_BOB_RESPONSE" "$BOB_DOC_1" "$BOB_DOC_2"
assert_rag_chunks_not_contain_any "$ALICE_QUERY_BOB_RESPONSE" "Bob 用户" "季度现金流预测" "绩效等级" "调薪窗口"

echo
echo "==> 6. 验证双方不能直接拿对方 kbId 调 RAG"
ALICE_ACCESS_BOB_RESPONSE="$(rag_ask "$ALICE_TOKEN" "$BOB_KB_ID" "季度现金流预测是什么？")"
assert_failed_code "$ALICE_ACCESS_BOB_RESPONSE" "Alice 访问 Bob 知识库 RAG" "404001"

BOB_ACCESS_ALICE_RESPONSE="$(rag_ask "$BOB_TOKEN" "$ALICE_KB_ID" "RabbitMQ 是什么？")"
assert_failed_code "$BOB_ACCESS_ALICE_RESPONSE" "Bob 访问 Alice 知识库 RAG" "404001"

echo
echo "RAG 权限隔离验证通过。"
