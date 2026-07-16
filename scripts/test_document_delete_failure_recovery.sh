#!/usr/bin/env bash
set -euo pipefail

# P0-3 文档删除失败恢复演练。
#
# 用法：
#   ./scripts/test_document_delete_failure_recovery.sh qdrant
#   ./scripts/test_document_delete_failure_recovery.sh minio
#
# qdrant 模式验证：vector 删除失败 -> DELETE_FAILED 且业务不可见 ->
# 恢复后 MySQL 二次过滤挡住残留 vector -> 再次 DELETE 成功。
# minio 模式验证：vector 已删除但 object 删除失败 -> DELETE_FAILED 且业务不可见 ->
# 恢复后幂等重试成功。
#
# 脚本不会自行停止/启动容器，也不会加入测试专用 API。故障点默认人工切换，
# 精确状态通过只读 MySQL 查询留证。

if [[ $# -ne 1 || ( "$1" != "qdrant" && "$1" != "minio" ) ]]; then
  echo "用法: $0 qdrant|minio" >&2
  exit 2
fi

TARGET="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/reliability_test_lib.sh
source "$SCRIPT_DIR/reliability_test_lib.sh"

QDRANT_URL="${QDRANT_URL:-http://localhost:6333}"
MINIO_URL="${MINIO_URL:-http://localhost:9000}"
USERNAME="delete_failure_${TARGET}_$RUN_ID"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ekb-delete-failure.XXXXXX")"
TARGET_DOC="$TEMP_DIR/delete-failure-target.txt"
CONTROL_DOC="$TEMP_DIR/delete-failure-control.txt"
TARGET_MARKER="DELETE_FAILURE_${TARGET}_TARGET_$RUN_ID"
CONTROL_MARKER="DELETE_FAILURE_${TARGET}_CONTROL_$RUN_ID"

cleanup() {
  rm -f "$TARGET_DOC" "$CONTROL_DOC"
  rmdir "$TEMP_DIR" 2>/dev/null || true
  if [[ -z "${TARGET_REACHABILITY_URL:-}" ]]; then
    return
  fi
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' "$TARGET_REACHABILITY_URL" 2>/dev/null || true)"
  if [[ "$code" == "000" ]]; then
    echo "警告：${TARGET_LABEL:-目标依赖} 当前仍不可访问，请在退出后手动恢复服务。" >&2
  fi
}
trap cleanup EXIT

assert_document_hidden() {
  local token="$1"
  local kb_id="$2"
  local document_id="$3"

  local detail_response
  local status_response
  local list_response
  detail_response="$(curl -sS "$BASE_URL/api/v1/documents/$document_id" \
    -H "Authorization: Bearer $token")"
  status_response="$(curl -sS "$BASE_URL/api/v1/documents/$document_id/index-status" \
    -H "Authorization: Bearer $token")"
  list_response="$(curl -sS "$BASE_URL/api/v1/knowledge-bases/$kb_id/documents?page=1&size=100" \
    -H "Authorization: Bearer $token")"

  assert_failed_code "$detail_response" "404002" "读取删除失败文档详情"
  assert_failed_code "$status_response" "404002" "读取删除失败文档索引状态"
  printf '%s' "$list_response" | python3 -c '
import json
import sys

document_id = str(sys.argv[1])
payload = json.load(sys.stdin)
if payload.get("success") is not True:
    print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
records = (payload.get("data") or {}).get("records", [])
if any(str(item.get("id")) == document_id for item in records):
    print("删除失败文档仍出现在文档列表", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$document_id"
}

assert_records_not_document_id() {
  local response="$1"
  local forbidden_document_id="$2"
  printf '%s' "$response" | python3 -c '
import json
import sys

forbidden_document_id = str(sys.argv[1])
payload = json.load(sys.stdin)
records = (payload.get("data") or {}).get("records", [])
if any(str(record.get("docId")) == forbidden_document_id for record in records):
    print(f"检索结果仍包含业务已删除 docId={forbidden_document_id}", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
' "$forbidden_document_id"
}

cat > "$TARGET_DOC" <<DOC
这是删除失败恢复演练的目标文档。
唯一标记：${TARGET_MARKER}。
第一次删除会在人为断开的 ${TARGET} 依赖处失败；文档必须立即业务不可见，
恢复依赖后再次调用同一个 DELETE 才完成外部资源清理。
DOC

cat > "$CONTROL_DOC" <<DOC
这是删除失败恢复演练的控制文档。
唯一标记：${CONTROL_MARKER}。
它始终保持 INDEXED，使目标文档业务删除后检索入口仍真实调用 Python/Qdrant，
而不是因为知识库没有已索引文档而在 Java 侧直接短路。
DOC

require_command mysql
if [[ "$TARGET" == "qdrant" ]]; then
  TARGET_LABEL="Qdrant"
  TARGET_REACHABILITY_URL="$QDRANT_URL/collections"
  EXPECTED_ERROR_CODE="503001"
else
  TARGET_LABEL="MinIO"
  TARGET_REACHABILITY_URL="$MINIO_URL/"
  EXPECTED_ERROR_CODE="503002"
fi

echo "==> 1. 确认目标依赖可访问并准备两份已索引文档"
wait_http_reachability "$TARGET_LABEL" "$TARGET_REACHABILITY_URL" "reachable" 5
TOKEN="$(register_and_login "$USERNAME" "Delete Failure $TARGET")"
KB_ID="$(create_knowledge_base "$TOKEN" "Delete Failure $TARGET KB $RUN_ID" "P0-3 delete failure recovery")"
TARGET_DOC_ID="$(upload_text_document "$TOKEN" "$KB_ID" "$TARGET_DOC")"
CONTROL_DOC_ID="$(upload_text_document "$TOKEN" "$KB_ID" "$CONTROL_DOC")"
wait_index_completed "$TOKEN" "$TARGET_DOC_ID" >/dev/null
wait_index_completed "$TOKEN" "$CONTROL_DOC_ID" >/dev/null

BEFORE_DELETE_SEARCH="$(search_knowledge_base "$TOKEN" "$KB_ID" "$TARGET_MARKER")"
assert_success_response "$BEFORE_DELETE_SEARCH" "删除前检索目标文档"
assert_records_contain "$BEFORE_DELETE_SEARCH" "$TARGET_MARKER"

echo "==> 2. 注入 $TARGET_LABEL 不可用故障"
pause_for_dependency_change "停止" "$TARGET_LABEL"
wait_http_reachability "$TARGET_LABEL" "$TARGET_REACHABILITY_URL" "unreachable"

echo "==> 3. 第一次 DELETE 应失败，但 MySQL 必须进入不可见的 DELETE_FAILED"
FIRST_DELETE_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/documents/$TARGET_DOC_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_failed_code "$FIRST_DELETE_RESPONSE" "$EXPECTED_ERROR_CODE" "依赖不可用时删除文档"
assert_document_delete_state "$TARGET_DOC_ID" "DELETE_FAILED" "1" "1"
assert_document_hidden "$TOKEN" "$KB_ID" "$TARGET_DOC_ID"

if [[ "$TARGET" == "minio" ]]; then
  echo "Qdrant 仍可用：确认业务检索不返回已经进入 DELETE_FAILED 的目标文档"
  SEARCH_WHILE_FAILED="$(search_knowledge_base "$TOKEN" "$KB_ID" "$TARGET_MARKER")"
  assert_success_response "$SEARCH_WHILE_FAILED" "MinIO 故障期间检索"
  assert_records_not_document_id "$SEARCH_WHILE_FAILED" "$TARGET_DOC_ID"
  assert_records_not_contain "$SEARCH_WHILE_FAILED" "$TARGET_MARKER"
fi

echo "==> 4. 恢复 $TARGET_LABEL"
pause_for_dependency_change "启动" "$TARGET_LABEL"
wait_http_reachability "$TARGET_LABEL" "$TARGET_REACHABILITY_URL" "reachable"

if [[ "$TARGET" == "qdrant" ]]; then
  echo "重试删除前先检索：Qdrant 中可能仍有目标 vectors，但 Java MySQL 二次过滤不得返回它"
  SEARCH_WITH_RESIDUAL="$(search_knowledge_base "$TOKEN" "$KB_ID" "$TARGET_MARKER")"
  assert_success_response "$SEARCH_WITH_RESIDUAL" "Qdrant 恢复后的残留向量检索"
  assert_records_not_document_id "$SEARCH_WITH_RESIDUAL" "$TARGET_DOC_ID"
  assert_records_not_contain "$SEARCH_WITH_RESIDUAL" "$TARGET_MARKER"
fi

echo "==> 5. 再次 DELETE，认领 generation=2 并完成幂等清理"
SECOND_DELETE_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/documents/$TARGET_DOC_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$SECOND_DELETE_RESPONSE" "恢复依赖后重试删除"
assert_document_delete_state "$TARGET_DOC_ID" "DELETED" "2" "0"

THIRD_DELETE_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/documents/$TARGET_DOC_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$THIRD_DELETE_RESPONSE" "DELETED 文档幂等删除"
assert_document_delete_state "$TARGET_DOC_ID" "DELETED" "2" "0"

echo "==> 6. 清理控制文档和知识库"
DELETE_CONTROL_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/documents/$CONTROL_DOC_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$DELETE_CONTROL_RESPONSE" "删除控制文档"
DELETE_KB_RESPONSE="$(curl -sS -X DELETE "$BASE_URL/api/v1/knowledge-bases/$KB_ID" \
  -H "Authorization: Bearer $TOKEN")"
assert_success_response "$DELETE_KB_RESPONSE" "删除演练知识库"

echo "$TARGET_LABEL 删除失败恢复演练通过。"
echo "targetDocumentId=$TARGET_DOC_ID controlDocumentId=$CONTROL_DOC_ID deleteGeneration=2"
