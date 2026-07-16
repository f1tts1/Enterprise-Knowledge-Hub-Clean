#!/usr/bin/env bash

# P0-3 可靠性演练共享函数。
# 该文件只封装测试用户、文档上传、状态轮询和只读证据查询；
# 不启动/停止任何服务，也不向生产代码加入故障注入入口。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "该文件只能被可靠性演练脚本 source，不能直接执行。" >&2
  exit 1
fi

RELIABILITY_ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)_$$}"
TEST_PASSWORD="${TEST_PASSWORD:-Password123}"

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "缺少命令: $command_name" >&2
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
    value = json.loads(raw)
    for part in path.split("."):
        value = value[part]
except (json.JSONDecodeError, KeyError, TypeError) as exc:
    print(f"无法从响应读取 {path}: {exc}", file=sys.stderr)
    print(raw, file=sys.stderr)
    sys.exit(1)
print(value)
' "$path"
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
    payload = json.loads(raw)
except json.JSONDecodeError as exc:
    print(f"{label} 未返回合法 JSON: {exc}", file=sys.stderr)
    print(raw, file=sys.stderr)
    sys.exit(1)
if payload.get("success") is not True:
    print(f"{label} 应该成功，但实际失败:", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$label"
}

assert_failed_code() {
  local response="$1"
  local expected_code="$2"
  local label="$3"
  printf '%s' "$response" | python3 -c '
import json
import sys

expected = sys.argv[1]
label = sys.argv[2]
raw = sys.stdin.read()
try:
    payload = json.loads(raw)
except json.JSONDecodeError as exc:
    print(f"{label} 未返回合法 JSON: {exc}", file=sys.stderr)
    print(raw, file=sys.stderr)
    sys.exit(1)
if payload.get("success") is not False or str(payload.get("code")) != expected:
    print(f"{label} 应失败且 code={expected}，实际响应:", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$expected_code" "$label"
}

post_json() {
  local url="$1"
  local token="$2"
  local body="$3"
  if [[ -n "$token" ]]; then
    curl -sS -X POST "$url" \
      -H "Authorization: Bearer $token" \
      -H "Content-Type: application/json" \
      -d "$body"
  else
    curl -sS -X POST "$url" \
      -H "Content-Type: application/json" \
      -d "$body"
  fi
}

register_and_login() {
  local username="$1"
  local nickname="$2"
  local register_response
  local login_response

  register_response="$(post_json "$BASE_URL/api/v1/auth/register" "" \
    "{\"username\":\"$username\",\"password\":\"$TEST_PASSWORD\",\"nickname\":\"$nickname\",\"email\":\"$username@example.com\"}")"
  assert_success_response "$register_response" "注册测试用户 $username"

  login_response="$(post_json "$BASE_URL/api/v1/auth/login" "" \
    "{\"username\":\"$username\",\"password\":\"$TEST_PASSWORD\"}")"
  assert_success_response "$login_response" "登录测试用户 $username"
  printf '%s' "$login_response" | json_get "data.accessToken"
}

create_knowledge_base() {
  local token="$1"
  local name="$2"
  local description="$3"
  local response
  response="$(post_json "$BASE_URL/api/v1/knowledge-bases" "$token" \
    "{\"name\":\"$name\",\"description\":\"$description\"}")"
  assert_success_response "$response" "创建知识库 $name"
  printf '%s' "$response" | json_get "data.id"
}

upload_text_document() {
  local token="$1"
  local kb_id="$2"
  local file="$3"
  local response
  response="$(curl -sS -X POST "$BASE_URL/api/v1/knowledge-bases/$kb_id/documents" \
    -H "Authorization: Bearer $token" \
    -F "file=@$file;type=text/plain")"
  assert_success_response "$response" "上传文档 $file"
  printf '%s' "$response" | json_get "data.id"
}

get_index_status() {
  local token="$1"
  local document_id="$2"
  curl -sS "$BASE_URL/api/v1/documents/$document_id/index-status" \
    -H "Authorization: Bearer $token"
}

wait_index_completed() {
  local token="$1"
  local document_id="$2"
  local attempts="${3:-90}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    local response
    local document_status
    local task_status
    response="$(get_index_status "$token" "$document_id")"
    assert_success_response "$response" "查询索引状态 documentId=$document_id"
    document_status="$(printf '%s' "$response" | json_get "data.documentIndexStatus")"
    task_status="$(printf '%s' "$response" | json_get "data.taskStatus")"
    echo "等待索引 documentId=$document_id: document=$document_status task=$task_status" >&2
    if [[ "$document_status" == "INDEXED" && "$task_status" == "SUCCESS" ]]; then
      printf '%s' "$response"
      return 0
    fi
    if [[ "$document_status" == "INDEX_FAILED" || "$task_status" == "FAILED" ]]; then
      echo "索引进入失败态:" >&2
      echo "$response" >&2
      return 1
    fi
    sleep 2
  done
  echo "等待索引完成超时: documentId=$document_id" >&2
  return 1
}

search_knowledge_base() {
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
payload = json.load(sys.stdin)
records = payload.get("data", {}).get("records", [])
joined = "\n".join((record.get("text") or "") for record in records)
if expected not in joined:
    print(f"检索结果缺少预期标记: {expected}", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$expected"
}

assert_records_not_contain() {
  local response="$1"
  local forbidden="$2"
  printf '%s' "$response" | python3 -c '
import json
import sys

forbidden = sys.argv[1]
payload = json.load(sys.stdin)
records = payload.get("data", {}).get("records", [])
joined = "\n".join((record.get("text") or "") for record in records)
if forbidden in joined:
    print(f"检索结果出现已禁止标记: {forbidden}", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$forbidden"
}

document_index_http_metric_count() {
  local token="$1"
  curl -fsS "$BASE_URL/actuator/prometheus" \
    -H "Authorization: Bearer $token" \
    | python3 -c '
import sys

prefix = "ekb_ai_http_duration_seconds_count"
total = 0.0
for line in sys.stdin:
    if line.startswith(prefix + "{") and "operation=\"document_index\"" in line:
        total += float(line.rsplit(" ", 1)[1])
print(f"{total:.0f}")
'
}

mysql_query() {
  local sql="$1"
  local database="${MYSQL_DATABASE:-enterprise_knowledge_hub}"
  local -a arguments
  arguments=(--batch --skip-column-names --raw)

  require_command mysql
  if [[ -n "${MYSQL_DEFAULTS_FILE:-}" ]]; then
    if [[ ! -f "$MYSQL_DEFAULTS_FILE" ]]; then
      echo "MYSQL_DEFAULTS_FILE 不存在: $MYSQL_DEFAULTS_FILE" >&2
      exit 1
    fi
    arguments=("--defaults-extra-file=$MYSQL_DEFAULTS_FILE" "${arguments[@]}")
    mysql "${arguments[@]}" "$database" -e "$sql"
    return
  fi

  arguments+=(
    --protocol=tcp
    -h "${MYSQL_HOST:-127.0.0.1}"
    -P "${MYSQL_PORT:-3306}"
    -u "${MYSQL_USER:-root}"
  )
  MYSQL_PWD="${MYSQL_PASSWORD:-}" mysql "${arguments[@]}" "$database" -e "$sql"
}

assert_document_delete_state() {
  local document_id="$1"
  local expected_status="$2"
  local expected_generation="$3"
  local expected_has_error="$4"
  local row
  local actual_status
  local actual_is_deleted
  local actual_generation
  local actual_has_error

  if [[ ! "$document_id" =~ ^[0-9]+$ ]]; then
    echo "documentId 不是正整数: $document_id" >&2
    exit 1
  fi
  row="$(mysql_query "SELECT index_status, is_deleted, delete_generation, IF(error_message IS NULL, 0, 1) FROM document WHERE id = $document_id;")"
  IFS=$'\t' read -r actual_status actual_is_deleted actual_generation actual_has_error <<EOF
$row
EOF
  if [[ "$actual_status" != "$expected_status" \
      || "$actual_is_deleted" != "1" \
      || "$actual_generation" != "$expected_generation" \
      || "$actual_has_error" != "$expected_has_error" ]]; then
    echo "删除状态断言失败: expected=$expected_status|1|$expected_generation|$expected_has_error actual=$row" >&2
    exit 1
  fi
}

wait_http_reachability() {
  local label="$1"
  local url="$2"
  local expected="$3"
  local attempts="${4:-60}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
    if [[ "$expected" == "reachable" && "$code" != "000" ]]; then
      return 0
    fi
    if [[ "$expected" == "unreachable" && "$code" == "000" ]]; then
      return 0
    fi
    echo "等待 $label 变为 $expected，当前 HTTP code=$code" >&2
    sleep 2
  done
  echo "$label 未在预期时间内变为 $expected: $url" >&2
  return 1
}

pause_for_dependency_change() {
  local action="$1"
  local label="$2"
  echo "请$action $label，保持其它依赖和 Java/FastAPI 继续运行，然后按 Enter。" >&2
  read -r _
}

require_command curl
require_command python3
