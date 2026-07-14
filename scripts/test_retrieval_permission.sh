#!/usr/bin/env bash
set -euo pipefail

# 这个脚本用于验证“不同用户只能检索自己知识库的内容”。
#
# 前置条件：
# 1. MySQL、Redis 在本机运行。
# 2. MinIO、Qdrant 在 Docker 中运行，并映射到 localhost:9000 / localhost:6333。
# 3. FastAPI AI 服务已启动在 http://localhost:8000，且 EMBEDDING_PROVIDER=local。
# 4. Spring Boot 后端已启动在 http://localhost:8080。
#
# 脚本会做的事：
# - 创建 Alice 和 Bob 两个用户。
# - Alice 上传 Alice 专属文档到 Alice 知识库。
# - Bob 上传 Bob 专属文档到 Bob 知识库。
# - 等待所有文档索引到 Qdrant。
# - Alice 只能搜到 Alice 文档，不能搜到 Bob 的私有财务/人事文档。
# - Bob 只能搜到 Bob 文档。
# - Alice 直接访问 Bob 的 kbId 会被 Java 权限校验拦截。
# - 删除 Alice 的一份文档后，Qdrant 中对应 chunk 不应再被检索返回。

BASE_URL="${BASE_URL:-http://localhost:8080}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC_DIR="$ROOT_DIR/tmp/retrieval-permission-test"
RUN_ID="$(date +%Y%m%d%H%M%S)"

ALICE_USERNAME="alice_perm_$RUN_ID"
BOB_USERNAME="bob_perm_$RUN_ID"
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

register_user() {
  local username="$1"
  local nickname="$2"
  local email="$3"

  echo "注册用户: $username"
  post_json "$BASE_URL/api/v1/auth/register" "" \
    "{\"username\":\"$username\",\"password\":\"$PASSWORD\",\"nickname\":\"$nickname\",\"email\":\"$email\"}" \
    >/dev/null
}

login_user() {
  local username="$1"

  post_json "$BASE_URL/api/v1/auth/login" "" \
    "{\"username\":\"$username\",\"password\":\"$PASSWORD\"}" \
    | json_get "data.accessToken"
}

create_kb() {
  local token="$1"
  local name="$2"

  post_json "$BASE_URL/api/v1/knowledge-bases" "$token" \
    "{\"name\":\"$name\",\"description\":\"permission retrieval test\"}" \
    | json_get "data.id"
}

upload_doc() {
  local token="$1"
  local kb_id="$2"
  local file="$3"

  # 这个函数的 stdout 会被命令替换捕获为 docId，所以日志必须写 stderr。
  # 否则提示文字会混进 docId，后续拼接 /documents/{docId}/index-status 时
  # curl 会得到一个带空格/中文/换行的畸形 URL。
  echo "上传文档: $file -> kbId=$kb_id" >&2
  curl -sS -X POST "$BASE_URL/api/v1/knowledge-bases/$kb_id/documents" \
    -H "Authorization: Bearer $token" \
    -F "file=@$file;type=text/plain" \
    | json_get "data.id"
}

delete_doc() {
  local token="$1"
  local doc_id="$2"

  curl -sS -X DELETE "$BASE_URL/api/v1/documents/$doc_id" \
    -H "Authorization: Bearer $token"
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

search_kb() {
  local token="$1"
  local kb_id="$2"
  local query="$3"

  post_json "$BASE_URL/api/v1/knowledge-bases/$kb_id/retrieval/search" "$token" \
    "{\"query\":\"$query\",\"topK\":5}"
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

assert_records_not_contain_any() {
  local response="$1"
  shift

  printf '%s' "$response" | python3 -c '
import json
import sys

forbidden_terms = sys.argv[1:]
obj = json.load(sys.stdin)
records = obj.get("data", {}).get("records", [])
joined = "\n".join((record.get("text") or "") for record in records)
for term in forbidden_terms:
    if term in joined:
        print("检索结果中出现了不应出现的其它用户内容:", term, file=sys.stderr)
        print(json.dumps(obj, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)
' "$@"
}

assert_failed_response() {
  local response="$1"
  local label="$2"
  local success
  success="$(printf '%s' "$response" | json_success)"

  if [[ "$success" != "false" ]]; then
    echo "$label 应该失败，但实际成功了:" >&2
    echo "$response" >&2
    exit 1
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

require_file "$DOC_DIR/alice-rabbitmq-indexing.txt"
require_file "$DOC_DIR/alice-qdrant-retrieval.txt"
require_file "$DOC_DIR/bob-private-finance.txt"
require_file "$DOC_DIR/bob-hr-policy.txt"

echo "测试后端地址: $BASE_URL"
echo "本次测试用户后缀: $RUN_ID"

register_user "$ALICE_USERNAME" "Alice Permission" "$ALICE_USERNAME@example.com"
register_user "$BOB_USERNAME" "Bob Permission" "$BOB_USERNAME@example.com"

ALICE_TOKEN="$(login_user "$ALICE_USERNAME")"
BOB_TOKEN="$(login_user "$BOB_USERNAME")"

ALICE_KB_ID="$(create_kb "$ALICE_TOKEN" "Alice Retrieval KB $RUN_ID")"
BOB_KB_ID="$(create_kb "$BOB_TOKEN" "Bob Retrieval KB $RUN_ID")"

echo "Alice kbId=$ALICE_KB_ID"
echo "Bob kbId=$BOB_KB_ID"

ALICE_DOC_1="$(upload_doc "$ALICE_TOKEN" "$ALICE_KB_ID" "$DOC_DIR/alice-rabbitmq-indexing.txt")"
ALICE_DOC_2="$(upload_doc "$ALICE_TOKEN" "$ALICE_KB_ID" "$DOC_DIR/alice-qdrant-retrieval.txt")"
BOB_DOC_1="$(upload_doc "$BOB_TOKEN" "$BOB_KB_ID" "$DOC_DIR/bob-private-finance.txt")"
BOB_DOC_2="$(upload_doc "$BOB_TOKEN" "$BOB_KB_ID" "$DOC_DIR/bob-hr-policy.txt")"

wait_index_completed "$ALICE_TOKEN" "$ALICE_DOC_1"
wait_index_completed "$ALICE_TOKEN" "$ALICE_DOC_2"
wait_index_completed "$BOB_TOKEN" "$BOB_DOC_1"
wait_index_completed "$BOB_TOKEN" "$BOB_DOC_2"

echo "验证 Alice 能搜到 Alice 自己的 RabbitMQ 文档"
ALICE_QUEUE_RESPONSE="$(search_kb "$ALICE_TOKEN" "$ALICE_KB_ID" "RabbitMQ 在当前项目中承担什么职责？")"
assert_records_contain "$ALICE_QUEUE_RESPONSE" "RabbitMQ 用于文档上传后的异步索引任务队列"
assert_records_not_contain_any "$ALICE_QUEUE_RESPONSE" "Bob 用户" "季度现金流预测" "调薪窗口"

echo "验证 Alice 能搜到 Alice 自己的 Qdrant 文档"
ALICE_QDRANT_RESPONSE="$(search_kb "$ALICE_TOKEN" "$ALICE_KB_ID" "Qdrant 如何做权限过滤？")"
assert_records_contain "$ALICE_QDRANT_RESPONSE" "检索时必须使用 ownerUserId 和 kbId 过滤"
assert_records_not_contain_any "$ALICE_QDRANT_RESPONSE" "Bob 用户" "财务预算" "绩效等级"

echo "删除 Alice 的 Qdrant 文档，并验证删除后不会再召回该文档 chunk"
DELETE_ALICE_QDRANT_RESPONSE="$(delete_doc "$ALICE_TOKEN" "$ALICE_DOC_2")"
assert_success_response "$DELETE_ALICE_QDRANT_RESPONSE" "删除 Alice Qdrant 文档"
ALICE_QDRANT_AFTER_DELETE_RESPONSE="$(search_kb "$ALICE_TOKEN" "$ALICE_KB_ID" "Qdrant 如何做权限过滤？")"
assert_records_not_contain_any "$ALICE_QDRANT_AFTER_DELETE_RESPONSE" "检索时必须使用 ownerUserId 和 kbId 过滤"

echo "验证 Alice 搜 Bob 关键词时不会返回 Bob 私有文档"
ALICE_BOB_QUERY_RESPONSE="$(search_kb "$ALICE_TOKEN" "$ALICE_KB_ID" "季度现金流预测和调薪窗口")"
assert_records_not_contain_any "$ALICE_BOB_QUERY_RESPONSE" "Bob 用户" "季度现金流预测" "供应商付款" "绩效等级" "调薪窗口"

echo "验证 Bob 能搜到 Bob 自己的财务文档"
BOB_FINANCE_RESPONSE="$(search_kb "$BOB_TOKEN" "$BOB_KB_ID" "季度现金流预测")"
assert_records_contain "$BOB_FINANCE_RESPONSE" "季度现金流预测"

echo "验证 Bob 能搜到 Bob 自己的人事文档"
BOB_HR_RESPONSE="$(search_kb "$BOB_TOKEN" "$BOB_KB_ID" "调薪窗口")"
assert_records_contain "$BOB_HR_RESPONSE" "调薪窗口"

echo "验证 Alice 不能直接访问 Bob 的 kbId"
ALICE_ACCESS_BOB_RESPONSE="$(search_kb "$ALICE_TOKEN" "$BOB_KB_ID" "季度现金流预测")"
assert_failed_response "$ALICE_ACCESS_BOB_RESPONSE" "Alice 访问 Bob 知识库"

echo "验证 Bob 不能直接访问 Alice 的 kbId"
BOB_ACCESS_ALICE_RESPONSE="$(search_kb "$BOB_TOKEN" "$ALICE_KB_ID" "RabbitMQ")"
assert_failed_response "$BOB_ACCESS_ALICE_RESPONSE" "Bob 访问 Alice 知识库"

echo "权限隔离与删除向量一致性检索测试通过。"
