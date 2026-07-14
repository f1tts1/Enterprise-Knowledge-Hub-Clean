#!/usr/bin/env bash
set -euo pipefail

# 验证同一知识库中“同内容文档删除后可重新上传，并且第二次删除不与历史 deleted 行冲突”。
#
# 前置条件：
# - MySQL 已执行 V2__fix_document_active_checksum_index.sql。
# - MySQL、Redis、MinIO、Qdrant、FastAPI、Spring Boot 已启动。

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="$(date +%Y%m%d%H%M%S)_$$"
USERNAME="doc_reupload_${RUN_ID}"
PASSWORD="Password123"
DEMO_DOC="$(mktemp "${TMPDIR:-/tmp}/ekb-reupload-delete.XXXXXX.txt")"

cleanup() {
  rm -f "$DEMO_DOC"
}
trap cleanup EXIT

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

upload_doc() {
  local token="$1"
  local kb_id="$2"
  local label="$3"
  local response

  response="$(curl -sS -X POST "$BASE_URL/api/v1/knowledge-bases/$kb_id/documents" \
    -H "Authorization: Bearer $token" \
    -F "file=@$DEMO_DOC;type=text/plain")"
  assert_success_response "$response" "$label 上传文档"
  printf '%s' "$response" | json_get "data.id"
}

delete_doc() {
  local token="$1"
  local doc_id="$2"
  local label="$3"
  local response

  response="$(curl -sS -X DELETE "$BASE_URL/api/v1/documents/$doc_id" \
    -H "Authorization: Bearer $token")"
  assert_success_response "$response" "$label 删除文档 docId=$doc_id"
}

cat > "$DEMO_DOC" <<DOC
同内容文档重复上传删除回归测试。
唯一标记：DOCUMENT_REUPLOAD_DELETE_${RUN_ID}。
这份文件会被上传、索引、删除，然后用完全相同内容再次上传、索引、删除。
DOC

echo "Java 后端地址: $BASE_URL"
echo "测试用户: $USERNAME"

echo
echo "==> 1. 注册并登录测试用户"
REGISTER_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/register" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"Document Reupload Delete\",\"email\":\"$USERNAME@example.com\"}")"
assert_success_response "$REGISTER_RESPONSE" "注册用户"

LOGIN_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/login" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
assert_success_response "$LOGIN_RESPONSE" "登录用户"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | json_get "data.accessToken")"

echo
echo "==> 2. 创建知识库"
CREATE_KB_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$TOKEN" \
  "{\"name\":\"Document Reupload Delete KB $RUN_ID\",\"description\":\"verify active-only checksum uniqueness\"}")"
assert_success_response "$CREATE_KB_RESPONSE" "创建知识库"
KB_ID="$(printf '%s' "$CREATE_KB_RESPONSE" | json_get "data.id")"
echo "kbId=$KB_ID"

echo
echo "==> 3. 第一次上传、索引、删除"
DOC_ID_1="$(upload_doc "$TOKEN" "$KB_ID" "第一次")"
echo "firstDocId=$DOC_ID_1"
wait_index_completed "$TOKEN" "$DOC_ID_1"
delete_doc "$TOKEN" "$DOC_ID_1" "第一次"

echo
echo "==> 4. 第二次上传同内容文件、索引、删除"
DOC_ID_2="$(upload_doc "$TOKEN" "$KB_ID" "第二次")"
echo "secondDocId=$DOC_ID_2"
wait_index_completed "$TOKEN" "$DOC_ID_2"
delete_doc "$TOKEN" "$DOC_ID_2" "第二次"

echo
echo "同内容文档重复上传删除验证通过。"
