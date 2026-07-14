#!/usr/bin/env bash
set -euo pipefail

# 验证知识库删除保护：
# 1. 知识库下还有未删除文档时，DELETE /knowledge-bases/{id} 必须返回 409。
# 2. 空知识库可以删除。
# 3. 空知识库删除后，同名知识库可以重新创建。

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="$(date +%Y%m%d%H%M%S)_$$"
USERNAME="kb_delete_guard_${RUN_ID}"
PASSWORD="Password123"
DEMO_DOC="$(mktemp "${TMPDIR:-/tmp}/ekb-kb-delete-guard.XXXXXX.txt")"
DOC_MARKER="KB_DELETE_GUARD_MARKER_${RUN_ID}"

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

json_code() {
  python3 -c '
import json
import sys

obj = json.load(sys.stdin)
print(obj.get("code"))
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

assert_failed_code() {
  local response="$1"
  local expected_code="$2"
  local label="$3"
  local success
  local code
  success="$(printf '%s' "$response" | json_success)"
  code="$(printf '%s' "$response" | json_code)"

  if [[ "$success" != "false" || "$code" != "$expected_code" ]]; then
    echo "$label 应该失败且 code=$expected_code，但实际响应如下:" >&2
    echo "$response" >&2
    exit 1
  fi
}

cat > "$DEMO_DOC" <<DOC
这是知识库删除保护验证文档。
唯一标记：${DOC_MARKER}。
只要这份文档仍是未删除状态，删除所属知识库就应该返回 409 Conflict。
DOC

echo "Java 后端地址: $BASE_URL"
echo "测试用户: $USERNAME"
echo "文档标记: $DOC_MARKER"

echo
echo "==> 1. 注册并登录测试用户"
REGISTER_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/register" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"KB Delete Guard\",\"email\":\"$USERNAME@example.com\"}")"
assert_success_response "$REGISTER_RESPONSE" "注册用户"

LOGIN_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/login" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
assert_success_response "$LOGIN_RESPONSE" "登录用户"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | json_get "data.accessToken")"

echo
echo "==> 2. 创建一个包含文档的知识库"
KB_WITH_DOC_NAME="KB Delete Guard With Doc ${RUN_ID}"
CREATE_KB_WITH_DOC_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$TOKEN" \
  "{\"name\":\"$KB_WITH_DOC_NAME\",\"description\":\"delete should be blocked while active documents exist\"}")"
assert_success_response "$CREATE_KB_WITH_DOC_RESPONSE" "创建带文档知识库"
KB_WITH_DOC_ID="$(printf '%s' "$CREATE_KB_WITH_DOC_RESPONSE" | json_get "data.id")"
echo "带文档 kbId=$KB_WITH_DOC_ID"

UPLOAD_RESPONSE="$(curl -sS -X POST "$BASE_URL/api/v1/knowledge-bases/$KB_WITH_DOC_ID/documents" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$DEMO_DOC;type=text/plain")"
assert_success_response "$UPLOAD_RESPONSE" "上传文档"
DOC_ID="$(printf '%s' "$UPLOAD_RESPONSE" | json_get "data.id")"
DOC_STATUS="$(printf '%s' "$UPLOAD_RESPONSE" | json_get "data.indexStatus")"
echo "docId=$DOC_ID indexStatus=$DOC_STATUS"

echo
echo "==> 3. 删除带文档知识库，预期返回 409"
DELETE_WITH_DOC_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/knowledge-bases/$KB_WITH_DOC_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_failed_code "$DELETE_WITH_DOC_RESPONSE" "409" "删除带文档知识库"
echo "已验证：存在未删除文档时，知识库删除被拦截。"

DETAIL_AFTER_FAILED_DELETE_RESPONSE="$(curl -sS "$BASE_URL/api/v1/knowledge-bases/$KB_WITH_DOC_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$DETAIL_AFTER_FAILED_DELETE_RESPONSE" "确认知识库仍可访问"
echo "已验证：删除被拦截后，知识库仍保持可访问。"

echo
echo "==> 4. 删除空知识库，预期成功"
EMPTY_KB_NAME="KB Delete Guard Empty ${RUN_ID}"
CREATE_EMPTY_KB_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$TOKEN" \
  "{\"name\":\"$EMPTY_KB_NAME\",\"description\":\"empty kb can be deleted\"}")"
assert_success_response "$CREATE_EMPTY_KB_RESPONSE" "创建空知识库"
EMPTY_KB_ID="$(printf '%s' "$CREATE_EMPTY_KB_RESPONSE" | json_get "data.id")"
echo "空知识库 kbId=$EMPTY_KB_ID"

DELETE_EMPTY_KB_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/knowledge-bases/$EMPTY_KB_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$DELETE_EMPTY_KB_RESPONSE" "删除空知识库"
echo "已验证：空知识库可以删除。"

DETAIL_DELETED_EMPTY_KB_RESPONSE="$(curl -sS "$BASE_URL/api/v1/knowledge-bases/$EMPTY_KB_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_failed_code "$DETAIL_DELETED_EMPTY_KB_RESPONSE" "404001" "查询已删除空知识库"
echo "已验证：删除后的知识库不再可见。"

echo
echo "==> 5. 使用同名重新创建知识库，验证名称已释放"
RECREATE_EMPTY_KB_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$TOKEN" \
  "{\"name\":\"$EMPTY_KB_NAME\",\"description\":\"same name should be reusable after delete\"}")"
assert_success_response "$RECREATE_EMPTY_KB_RESPONSE" "同名重建知识库"
RECREATED_KB_ID="$(printf '%s' "$RECREATE_EMPTY_KB_RESPONSE" | json_get "data.id")"
echo "同名重建 kbId=$RECREATED_KB_ID"

echo
echo "知识库删除保护验证通过。"
