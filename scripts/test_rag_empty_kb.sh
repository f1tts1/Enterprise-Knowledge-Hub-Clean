#!/usr/bin/env bash
set -euo pipefail

# 验证空知识库的 RAG 问答短路：
# 知识库存在但没有任何 INDEXED 文档时，Java 应直接返回无上下文答案，
# 不调用 Python、Qdrant 或 LLM。

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="$(date +%Y%m%d%H%M%S)_$$"
USERNAME="rag_empty_${RUN_ID}"
PASSWORD="Password123"
EXPECTED_ANSWER="当前知识库没有可用于回答该问题的已索引内容。"

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

assert_empty_rag_response() {
  local response="$1"
  local expected_answer="$2"

  printf '%s' "$response" | python3 -c '
import json
import sys

expected_answer = sys.argv[1]
obj = json.load(sys.stdin)
data = obj.get("data") or {}
errors = []

if data.get("answer") != expected_answer:
    errors.append("answer 不符合空知识库预期")
if data.get("citations") != []:
    errors.append("citations 应为空数组")
if data.get("retrievedChunks") != []:
    errors.append("retrievedChunks 应为空数组")
if data.get("llmProvider") is not None:
    errors.append("llmProvider 应为 null")
if data.get("llmModel") is not None:
    errors.append("llmModel 应为 null")

if errors:
    print("空知识库 RAG 响应校验失败:", "；".join(errors), file=sys.stderr)
    print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$expected_answer"
}

echo "Java 后端地址: $BASE_URL"
echo "测试用户: $USERNAME"

echo
echo "==> 1. 注册并登录测试用户"
REGISTER_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/register" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"RAG Empty Test\",\"email\":\"$USERNAME@example.com\"}")"
assert_success_response "$REGISTER_RESPONSE" "注册用户"

LOGIN_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/login" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
assert_success_response "$LOGIN_RESPONSE" "登录用户"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | json_get "data.accessToken")"

echo
echo "==> 2. 创建空知识库"
CREATE_KB_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$TOKEN" \
  "{\"name\":\"RAG Empty KB $RUN_ID\",\"description\":\"rag empty kb shortcut verification\"}")"
assert_success_response "$CREATE_KB_RESPONSE" "创建空知识库"
KB_ID="$(printf '%s' "$CREATE_KB_RESPONSE" | json_get "data.id")"
echo "kbId=$KB_ID"

echo
echo "==> 3. 调用 RAG 问答，预期 Java 直接短路为空上下文答案"
RAG_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases/$KB_ID/rag/ask" "$TOKEN" \
  '{"question":"这个空知识库里有什么内容？","topK":5}')"
assert_success_response "$RAG_RESPONSE" "空知识库 RAG 问答"
assert_empty_rag_response "$RAG_RESPONSE" "$EXPECTED_ANSWER"

echo
echo "空知识库 RAG 短路验证通过。"
