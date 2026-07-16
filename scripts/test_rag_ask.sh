#!/usr/bin/env bash
set -euo pipefail

# 验证最小同步 RAG 问答接口。
#
# 前置条件：
# - MySQL、Redis、MinIO、Qdrant 已启动。
# - FastAPI 已启动，并且其进程能读取 DEEPSEEK_API_KEY 或显式 LLM_* 配置。
# - Spring Boot 后端已启动。

BASE_URL="${BASE_URL:-http://localhost:8080}"
AI_SERVICE_URL="${AI_SERVICE_URL:-http://localhost:8000}"
RUN_ID="$(date +%Y%m%d%H%M%S)_$$"
USERNAME="rag_ask_${RUN_ID}"
PASSWORD="Password123"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ekb-rag-ask.XXXXXX")"
DEMO_DOC="$TEMP_DIR/rag-ask.txt"
UNIQUE_MARKER="RAG_ASK_MARKER_${RUN_ID}"
EXPECTED_FACT="玄武湖项目使用 RabbitMQ 承担文档上传后的异步索引队列"

cleanup() {
  rm -f "$DEMO_DOC"
  rmdir "$TEMP_DIR" 2>/dev/null || true
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

assert_rag_response_valid() {
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
    errors.append("retrievedChunks 未包含测试事实")

if errors:
    print("RAG 响应校验失败:", "；".join(errors), file=sys.stderr)
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
  [[ "$http_code" != "000" ]]
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

if [[ -z "${DEEPSEEK_API_KEY:-}" && -z "${LLM_API_KEY:-}" ]]; then
  echo "提示：当前 shell 未检测到 DEEPSEEK_API_KEY 或 LLM_API_KEY。"
  echo "脚本仍会继续执行；只要已启动的 FastAPI 进程能读取 LLM 配置即可。"
fi

if ! ai_service_reachable; then
  echo "FastAPI AI 服务不可访问: $AI_SERVICE_URL" >&2
  exit 1
fi

cat > "$DEMO_DOC" <<DOC
这是最小 RAG 问答端到端测试文档。
唯一标记：${UNIQUE_MARKER}。
${EXPECTED_FACT}。
Spring Boot 负责鉴权、知识库 owner 校验、文档元数据和任务状态维护。
FastAPI 负责从 MinIO 下载文件、解析切分文本、生成本地 embedding，并写入 Qdrant。
RAG 问答必须先检索已索引 chunk，再基于这些 chunk 调用 LLM 生成答案。
DOC

echo "Java 后端地址: $BASE_URL"
echo "FastAPI AI 服务地址: $AI_SERVICE_URL"
echo "测试用户: $USERNAME"
echo "唯一标记: $UNIQUE_MARKER"

echo
echo "==> 1. 注册并登录测试用户"
REGISTER_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/register" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"nickname\":\"RAG Ask Test\",\"email\":\"$USERNAME@example.com\"}")"
assert_success_response "$REGISTER_RESPONSE" "注册用户"

LOGIN_RESPONSE="$(post_json "$BASE_URL/api/v1/auth/login" "" \
  "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
assert_success_response "$LOGIN_RESPONSE" "登录用户"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | json_get "data.accessToken")"

echo
echo "==> 2. 创建知识库并上传测试文档"
CREATE_KB_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$TOKEN" \
  "{\"name\":\"RAG Ask KB $RUN_ID\",\"description\":\"rag ask e2e verification\"}")"
assert_success_response "$CREATE_KB_RESPONSE" "创建知识库"
KB_ID="$(printf '%s' "$CREATE_KB_RESPONSE" | json_get "data.id")"
echo "kbId=$KB_ID"

UPLOAD_RESPONSE="$(curl -sS -X POST "$BASE_URL/api/v1/knowledge-bases/$KB_ID/documents" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$DEMO_DOC;type=text/plain")"
assert_success_response "$UPLOAD_RESPONSE" "上传文档"
DOC_ID="$(printf '%s' "$UPLOAD_RESPONSE" | json_get "data.id")"
echo "docId=$DOC_ID"

echo
echo "==> 3. 等待文档索引完成"
wait_index_completed "$TOKEN" "$DOC_ID"

echo
echo "==> 4. 调用 RAG 问答接口"
RAG_RESPONSE="$(post_json "$BASE_URL/api/v1/knowledge-bases/$KB_ID/rag/ask" "$TOKEN" \
  "{\"question\":\"玄武湖项目用 RabbitMQ 解决了什么问题？\",\"topK\":5}")"
assert_success_response "$RAG_RESPONSE" "RAG 问答"
assert_rag_response_valid "$RAG_RESPONSE" "$EXPECTED_FACT"

echo
echo "RAG 问答端到端验证通过。"
