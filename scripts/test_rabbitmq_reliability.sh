#!/usr/bin/env bash
set -euo pipefail

# P0-3 RabbitMQ 可靠性故障演练：
# 1. broker 不可用时上传仍提交 MySQL，task 保持 PENDING；
# 2. broker 恢复后由 PENDING scheduler 自动重投同一 attempt；
# 3. PENDING 文档删除被 409004 拦截；
# 4. 终态重复消息不重复调用 embedding；
# 5. 非法 JSON 被路由到 DLQ。
#
# 脚本不会自行停止或启动 RabbitMQ，会在故障注入点暂停，由操作者
# 分阶段控制本地 broker。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/reliability_test_lib.sh
source "$SCRIPT_DIR/reliability_test_lib.sh"

RABBITMQ_MANAGEMENT_URL="${RABBITMQ_MANAGEMENT_URL:-http://localhost:15672}"
RABBITMQ_VIRTUAL_HOST="${RABBITMQ_VIRTUAL_HOST:-/}"
RABBITMQ_EXCHANGE="${INDEXING_QUEUE_EXCHANGE:-ekb.indexing.exchange}"
RABBITMQ_ROUTING_KEY="${INDEXING_QUEUE_ROUTING_KEY:-indexing.task}"
RABBITMQ_QUEUE="${INDEXING_QUEUE_NAME:-ekb.indexing.tasks}"
RABBITMQ_DLQ="${INDEXING_QUEUE_DLQ:-ekb.indexing.tasks.dlq}"
PENDING_OBSERVE_SECONDS="${PENDING_OBSERVE_SECONDS:-35}"

USERNAME="rabbit_reliability_$RUN_ID"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ekb-rabbit-reliability.XXXXXX")"
DEMO_DOC="$TEMP_DIR/rabbit-reliability.txt"
RABBITMQ_CURL_CONFIG="$TEMP_DIR/rabbitmq-curl.conf"
UNIQUE_MARKER="RABBIT_RELIABILITY_MARKER_$RUN_ID"

cleanup() {
  rm -f "$DEMO_DOC"
  if [[ -f "$RABBITMQ_CURL_CONFIG" ]] \
      && ! rabbitmq_api_reachable; then
    echo "警告：RabbitMQ 当前仍不可访问，请在退出后手动恢复服务。" >&2
  fi
  rm -f "$RABBITMQ_CURL_CONFIG"
  rmdir "$TEMP_DIR" 2>/dev/null || true
}
trap cleanup EXIT

require_rabbitmq_credentials() {
  if [[ -z "${RABBITMQ_USERNAME:-}" || -z "${RABBITMQ_PASSWORD:-}" ]]; then
    echo "请通过环境变量提供 RABBITMQ_USERNAME 和 RABBITMQ_PASSWORD；脚本不会打印凭证。" >&2
    exit 1
  fi
}

prepare_rabbitmq_curl_config() {
  local credentials="$RABBITMQ_USERNAME:$RABBITMQ_PASSWORD"
  if [[ "$credentials" == *$'\n'* || "$credentials" == *$'\r'* ]]; then
    echo "RabbitMQ 凭证不能包含换行符。" >&2
    exit 1
  fi
  credentials="${credentials//\\/\\\\}"
  credentials="${credentials//\"/\\\"}"
  (umask 077; printf 'user = "%s"\n' "$credentials" > "$RABBITMQ_CURL_CONFIG")
}

url_encode() {
  python3 -c 'import sys; from urllib.parse import quote; print(quote(sys.argv[1], safe=""))' "$1"
}

rabbitmq_api_get() {
  local path="$1"
  curl -fsS --config "$RABBITMQ_CURL_CONFIG" \
    "$RABBITMQ_MANAGEMENT_URL$path"
}

rabbitmq_api_reachable() {
  rabbitmq_api_get "/api/overview" >/dev/null 2>&1
}

wait_rabbitmq_state() {
  local expected="$1"
  local attempts="${2:-60}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    if [[ "$expected" == "reachable" ]] && rabbitmq_api_reachable; then
      return 0
    fi
    if [[ "$expected" == "unreachable" ]] && ! rabbitmq_api_reachable; then
      return 0
    fi
    echo "等待 RabbitMQ management API 变为 $expected ..." >&2
    sleep 2
  done
  echo "RabbitMQ 未在预期时间内变为 $expected" >&2
  return 1
}

rabbitmq_queue_messages() {
  local queue_name="$1"
  local encoded_vhost
  local encoded_queue
  encoded_vhost="$(url_encode "$RABBITMQ_VIRTUAL_HOST")"
  encoded_queue="$(url_encode "$queue_name")"
  rabbitmq_api_get "/api/queues/$encoded_vhost/$encoded_queue" \
    | python3 -c 'import json, sys; print(int(json.load(sys.stdin).get("messages", 0)))'
}

wait_queue_idle() {
  local attempts="${1:-30}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    if [[ "$(rabbitmq_queue_messages "$RABBITMQ_QUEUE")" == "0" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "等待 RabbitMQ 主队列消费完成超时" >&2
  return 1
}

wait_dlq_increment() {
  local previous_count="$1"
  local attempts="${2:-30}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    local current_count
    current_count="$(rabbitmq_queue_messages "$RABBITMQ_DLQ")"
    if [[ "$current_count" -gt "$previous_count" ]]; then
      printf '%s' "$current_count"
      return 0
    fi
    sleep 1
  done
  echo "等待非法消息进入 DLQ 超时: before=$previous_count" >&2
  return 1
}

publish_payload() {
  local payload="$1"
  local encoded_vhost
  local encoded_exchange
  local body
  local response
  encoded_vhost="$(url_encode "$RABBITMQ_VIRTUAL_HOST")"
  encoded_exchange="$(url_encode "$RABBITMQ_EXCHANGE")"
  body="$(python3 -c '
import json
import sys

print(json.dumps({
    "properties": {"content_type": "application/json"},
    "routing_key": sys.argv[1],
    "payload": sys.argv[2],
    "payload_encoding": "string",
}, separators=(",", ":")))
' "$RABBITMQ_ROUTING_KEY" "$payload")"
  response="$(curl -fsS -X POST \
    --config "$RABBITMQ_CURL_CONFIG" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "$RABBITMQ_MANAGEMENT_URL/api/exchanges/$encoded_vhost/$encoded_exchange/publish")"
  printf '%s' "$response" | python3 -c '
import json
import sys

payload = json.load(sys.stdin)
if payload.get("routed") is not True:
    print("RabbitMQ publish 没有路由到队列", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False), file=sys.stderr)
    sys.exit(1)
'
}

assert_index_identity() {
  local response="$1"
  local expected_document_status="$2"
  local expected_task_status="$3"
  local expected_task_id="$4"
  printf '%s' "$response" | python3 -c '
import json
import sys

expected_document_status, expected_task_status, expected_task_id = sys.argv[1:]
payload = json.load(sys.stdin)
data = payload.get("data") or {}
actual = (
    str(data.get("documentIndexStatus")),
    str(data.get("taskStatus")),
    str(data.get("indexingTaskId")),
    str(data.get("attemptNo")),
    str(data.get("retryCount")),
    str(data.get("triggerType")),
)
expected = (
    expected_document_status,
    expected_task_status,
    expected_task_id,
    "0",
    "0",
    "UPLOAD",
)
if actual != expected:
    print(f"索引 attempt 身份断言失败: expected={expected}, actual={actual}", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$expected_document_status" "$expected_task_status" "$expected_task_id"
}

model_log_count() {
  local task_id="$1"
  if [[ ! "$task_id" =~ ^[0-9]+$ ]]; then
    echo "taskId 不是正整数: $task_id" >&2
    exit 1
  fi
  mysql_query "SELECT COUNT(*) FROM model_call_log WHERE request_id = 'index-task-$task_id' AND call_type = 'EMBEDDING';"
}

cat > "$DEMO_DOC" <<DOC
这是 RabbitMQ 可靠性故障演练文档。
唯一标记：${UNIQUE_MARKER}。
broker 暂时不可用时，MySQL 中的 document 和 indexing_task 必须保持 PENDING；
恢复后 scheduler 应重投同一个 attempt，重复终态消息不得再次执行 embedding。
DOC

require_rabbitmq_credentials
prepare_rabbitmq_curl_config
require_command mysql
if ! rabbitmq_api_reachable; then
  echo "开始演练前 RabbitMQ management API 必须可访问: $RABBITMQ_MANAGEMENT_URL" >&2
  exit 1
fi

echo "==> 1. 创建测试用户和知识库"
TOKEN="$(register_and_login "$USERNAME" "Rabbit Reliability")"
KB_ID="$(create_knowledge_base "$TOKEN" "Rabbit Reliability KB $RUN_ID" "P0-3 RabbitMQ reliability drill")"

echo "==> 2. 注入 RabbitMQ 不可用故障"
pause_for_dependency_change "停止" "RabbitMQ"
wait_rabbitmq_state "unreachable"

echo "==> 3. broker 不可用时上传，验证 PENDING 事实状态"
DOC_ID="$(upload_text_document "$TOKEN" "$KB_ID" "$DEMO_DOC")"
PENDING_RESPONSE="$(get_index_status "$TOKEN" "$DOC_ID")"
assert_success_response "$PENDING_RESPONSE" "查询 PENDING 索引状态"
TASK_ID="$(printf '%s' "$PENDING_RESPONSE" | json_get "data.indexingTaskId")"
assert_index_identity "$PENDING_RESPONSE" "PENDING_INDEX" "PENDING" "$TASK_ID"

echo "观察 ${PENDING_OBSERVE_SECONDS}s，确认失败发布不增加 retry/attempt"
sleep "$PENDING_OBSERVE_SECONDS"
PENDING_AFTER_WAIT="$(get_index_status "$TOKEN" "$DOC_ID")"
assert_index_identity "$PENDING_AFTER_WAIT" "PENDING_INDEX" "PENDING" "$TASK_ID"

DELETE_BUSY_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/documents/$DOC_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_failed_code "$DELETE_BUSY_RESPONSE" "409004" "删除 PENDING_INDEX 文档"

echo "==> 4. 恢复 RabbitMQ，等待 scheduler 自动重投（不调用 index-retry）"
pause_for_dependency_change "启动" "RabbitMQ"
wait_rabbitmq_state "reachable"
INDEXED_RESPONSE="$(wait_index_completed "$TOKEN" "$DOC_ID" 120)"
assert_index_identity "$INDEXED_RESPONSE" "INDEXED" "SUCCESS" "$TASK_ID"

SEARCH_RESPONSE="$(search_knowledge_base "$TOKEN" "$KB_ID" "$UNIQUE_MARKER")"
assert_success_response "$SEARCH_RESPONSE" "检索恢复后的文档"
assert_records_contain "$SEARCH_RESPONSE" "$UNIQUE_MARKER"

echo "==> 5. 重投同一终态消息，验证不重复执行 embedding"
MODEL_LOG_COUNT_BEFORE="$(model_log_count "$TASK_ID")"
HTTP_COUNT_BEFORE="$(document_index_http_metric_count "$TOKEN")"
if [[ "$MODEL_LOG_COUNT_BEFORE" -lt 1 || "$HTTP_COUNT_BEFORE" -lt 1 ]]; then
  echo "缺少本次成功索引的 model_call_log 或 document_index HTTP 指标，无法建立重复消息对照基线" >&2
  exit 1
fi
publish_payload "{\"documentId\":$DOC_ID,\"indexingTaskId\":$TASK_ID}"
wait_queue_idle
sleep 2

AFTER_DUPLICATE_RESPONSE="$(get_index_status "$TOKEN" "$DOC_ID")"
assert_index_identity "$AFTER_DUPLICATE_RESPONSE" "INDEXED" "SUCCESS" "$TASK_ID"
MODEL_LOG_COUNT_AFTER="$(model_log_count "$TASK_ID")"
HTTP_COUNT_AFTER="$(document_index_http_metric_count "$TOKEN")"
if [[ "$MODEL_LOG_COUNT_AFTER" != "$MODEL_LOG_COUNT_BEFORE" \
    || "$HTTP_COUNT_AFTER" != "$HTTP_COUNT_BEFORE" ]]; then
  echo "重复消息产生了新的模型调用记录或 Java→Python 索引 HTTP 尝试: modelLog $MODEL_LOG_COUNT_BEFORE->$MODEL_LOG_COUNT_AFTER, documentIndexHttp $HTTP_COUNT_BEFORE->$HTTP_COUNT_AFTER" >&2
  exit 1
fi

echo "==> 6. 投递非法 JSON，验证 DLQ 增量"
DLQ_BEFORE="$(rabbitmq_queue_messages "$RABBITMQ_DLQ")"
publish_payload "not-valid-indexing-json"
wait_queue_idle
DLQ_AFTER="$(wait_dlq_increment "$DLQ_BEFORE")"

echo "==> 7. 清理业务资源"
DELETE_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/documents/$DOC_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$DELETE_RESPONSE" "删除演练文档"
DELETE_KB_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/knowledge-bases/$KB_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$DELETE_KB_RESPONSE" "删除演练知识库"

echo "RabbitMQ 可靠性演练通过。"
echo "documentId=$DOC_ID taskId=$TASK_ID modelLogCount=$MODEL_LOG_COUNT_AFTER documentIndexHttpCount=$HTTP_COUNT_AFTER dlqBefore=$DLQ_BEFORE dlqAfter=$DLQ_AFTER"
echo "为避免误删既有死信，脚本不会自动 purge DLQ；本次非法消息保留供人工查看。"
