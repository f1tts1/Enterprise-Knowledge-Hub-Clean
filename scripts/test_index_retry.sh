#!/usr/bin/env bash
set -euo pipefail

# 验证索引失败后的人工重试入口。
#
# 使用方式是先让 FastAPI AI 服务暂时不可用，再运行本脚本。
# 脚本会上传一份 TXT 文档，等待 Java 将索引任务标记为 INDEX_FAILED/FAILED；
# 随后提示你恢复 FastAPI，再调用 /index-retry，并验证最终 INDEXED/SUCCESS
# 以及检索可召回。

BASE_URL="${BASE_URL:-http://localhost:8080}"
AI_SERVICE_URL="${AI_SERVICE_URL:-http://localhost:8000}"
RUN_ID="$(date +%Y%m%d%H%M%S)"
USERNAME="retry_test_$RUN_ID"
PASSWORD="Password123"
DEMO_DOC="$(mktemp "${TMPDIR:-/tmp}/ekb-index-retry.XXXXXX.txt")"
UNIQUE_MARKER="INDEX_RETRY_MARKER_$RUN_ID"

cleanup() {
  rm -f "$DEMO_DOC"
}
trap cleanup EXIT

json_get() {
  local path="$1"
  python3 -c '
import json
import sys

obj = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    obj = obj[part]
print(obj)
' "$path"
}

json_success() {
  python3 -c '
import json
import sys

obj = json.load(sys.stdin)
print(str(obj.get("success")).lower())
'
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
  local success
  success="$(printf '%s' "$response" | json_success)"

  if [[ "$success" != "true" ]]; then
    echo "$label 应该成功，但实际失败了:" >&2
    echo "$response" >&2
    exit 1
  fi
}

assert_records_contain() {
  local response="$1"
  local expected="$2"

  printf '%s' "$response" | python3 -c '
import json
import sys

expected = sys.argv[1]
obj = json.load(sys.stdin)
records = obj.get("data", {}).get("records", [])
joined = "\n".join((record.get("text") or "") for record in records)
if expected not in joined:
    print("未在检索结果中找到期望内容:", expected, file=sys.stderr)
    print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$expected"
}

ai_service_reachable() {
  local http_code
  http_code="$(curl -sS -o /dev/null -w "%{http_code}" \
    -X POST "$AI_SERVICE_URL/api/v1/retrieval/search" \
    -H "Content-Type: application/json" \
    -d '{}' 2>/dev/null || true)"

  # FastAPI 存活时，非法请求通常返回 422；如果后续改了校验，也可能是 400/500。
  # 这里只判断“能连上 HTTP 服务”，不把它当健康检查接口。
  [[ "$http_code" != "000" ]]
}

wait_ai_unreachable() {
  if ! ai_service_reachable; then
    return 0
  fi

  echo "检测到 FastAPI AI 服务当前可访问: $AI_SERVICE_URL"
  echo "请先停止 FastAPI AI 服务，让本次上传的索引任务真实失败。"
  echo "停止后按 Enter 继续。"
  read -r _

  for _ in {1..30}; do
    if ! ai_service_reachable; then
      return 0
    fi
    echo "仍能访问 FastAPI，继续等待服务停止..."
    sleep 2
  done

  echo "FastAPI 仍然可访问，无法验证索引失败重试入口。" >&2
  exit 1
}

wait_ai_reachable() {
  if ai_service_reachable; then
    return 0
  fi

  echo "现在请重新启动 FastAPI AI 服务: $AI_SERVICE_URL"
  echo "启动完成后按 Enter 继续。"
  read -r _

  for _ in {1..60}; do
    if ai_service_reachable; then
      return 0
    fi
    echo "等待 FastAPI 可访问..."
    sleep 2
  done

  echo "FastAPI 仍不可访问，无法继续重试验证。" >&2
  exit 1
}

wait_index_failed() {
  local token="$1"
  local doc_id="$2"
  local attempts="${3:-60}"

  for ((i = 1; i <= attempts; i++)); do
    local response
    response="$(curl -sS "$BASE_URL/api/v1/documents/$doc_id/index-status" \
      -H "Authorization: Bearer $token")"

    local doc_status
    local task_status
    doc_status="$(printf '%s' "$response" | json_get "data.documentIndexStatus")"
    task_status="$(printf '%s' "$response" | json_get "data.taskStatus")"

    echo "等待失败态 docId=$doc_id: document=$doc_status task=$task_status"

    if [[ "$doc_status" == "INDEX_FAILED" && "$task_status" == "FAILED" ]]; then
      return 0
    fi

    if [[ "$doc_status" == "INDEXED" || "$task_status" == "SUCCESS" ]]; then
      echo "文档索引成功了，说明失败条件没有生效。请确认上传时 FastAPI 已停止。" >&2
      echo "$response" >&2
      exit 1
    fi

    sleep 2
  done

  echo "等待索引失败态超时: docId=$doc_id" >&2
  exit 1
}

wait_index_completed() {
  local token="$1"
  local doc_id="$2"
  local attempts="${3:-90}"

  for ((i = 1; i <= attempts; i++)); do
    local response
    response="$(curl -sS "$BASE_URL/api/v1/documents/$doc_id/index-status" \
      -H "Authorization: Bearer $token")"

    local doc_status
    local task_status
    doc_status="$(printf '%s' "$response" | json_get "data.documentIndexStatus")"
    task_status="$(printf '%s' "$response" | json_get "data.taskStatus")"

    echo "等待重试完成 docId=$doc_id: document=$doc_status task=$task_status"

    if [[ "$doc_status" == "INDEXED" && "$task_status" == "SUCCESS" ]]; then
      return 0
    fi

    if [[ "$doc_status" == "INDEX_FAILED" || "$task_status" == "FAILED" ]]; then
      echo "重试后仍然索引失败，响应如下:" >&2
      echo "$response" >&2
      exit 1
    fi

    sleep 2
  done

  echo "等待重试索引完成超时: docId=$doc_id" >&2
  exit 1
}

cat > "$DEMO_DOC" <<DOC
这是索引失败人工重试验证文档。
唯一标记：${UNIQUE_MARKER}。
Java 后端应该在 AI 服务不可用时把 document.index_status 写成 INDEX_FAILED，
把 indexing_task.status 写成 FAILED。
恢复 AI 服务后，调用 index-retry 应该让同一任务重新进入 PENDING 并最终写入 Qdrant。
DOC

echo "Java 后端地址: $BASE_URL"
echo "FastAPI AI 服务地址: $AI_SERVICE_URL"
echo "测试用户: $USERNAME"
echo "唯一检索标记: $UNIQUE_MARKER"

wait_ai_unreachable

echo
echo "==> 1. 注册并登录测试用户"
REGISTER_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/register" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"Index Retry Test\",\"email\":\"$USERNAME@example.com\"}")"
assert_success_response "$REGISTER_RESPONSE" "注册用户"

LOGIN_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/login" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
assert_success_response "$LOGIN_RESPONSE" "登录用户"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | json_get "data.accessToken")"

echo
echo "==> 2. 创建知识库"
CREATE_KB_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$TOKEN" \
  "{\"name\":\"Index Retry KB $RUN_ID\",\"description\":\"index retry verification\"}")"
assert_success_response "$CREATE_KB_RESPONSE" "创建知识库"
KB_ID="$(printf '%s' "$CREATE_KB_RESPONSE" | json_get "data.id")"
echo "kbId=$KB_ID"

echo
echo "==> 3. FastAPI 不可用时上传文档，等待索引失败"
UPLOAD_RESPONSE="$(curl -sS -X POST "$BASE_URL/api/v1/knowledge-bases/$KB_ID/documents" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$DEMO_DOC;type=text/plain")"
assert_success_response "$UPLOAD_RESPONSE" "上传文档"
DOC_ID="$(printf '%s' "$UPLOAD_RESPONSE" | json_get "data.id")"
echo "docId=$DOC_ID"

wait_index_failed "$TOKEN" "$DOC_ID"
echo "已验证失败态: document=INDEX_FAILED task=FAILED"

echo
echo "==> 4. 恢复 FastAPI 后调用 index-retry"
wait_ai_reachable

RETRY_RESPONSE="$(curl -sS -X POST "$BASE_URL/api/v1/documents/$DOC_ID/index-retry" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$RETRY_RESPONSE" "重试索引"
RETRY_DOC_STATUS="$(printf '%s' "$RETRY_RESPONSE" | json_get "data.documentIndexStatus")"
RETRY_TASK_STATUS="$(printf '%s' "$RETRY_RESPONSE" | json_get "data.taskStatus")"
RETRY_COUNT="$(printf '%s' "$RETRY_RESPONSE" | json_get "data.retryCount")"
echo "重试入口返回: document=$RETRY_DOC_STATUS task=$RETRY_TASK_STATUS retryCount=$RETRY_COUNT"

wait_index_completed "$TOKEN" "$DOC_ID"
echo "已验证重试成功: document=INDEXED task=SUCCESS"

echo
echo "==> 5. 检索验证重试后的 Qdrant chunk 可召回"
SEARCH_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases/$KB_ID/retrieval/search" "$TOKEN" \
  "{\"query\":\"$UNIQUE_MARKER\",\"topK\":5}")"
assert_success_response "$SEARCH_RESPONSE" "检索重试文档"
assert_records_contain "$SEARCH_RESPONSE" "$UNIQUE_MARKER"

echo
echo "索引失败人工重试验证通过。"
