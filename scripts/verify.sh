#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
MVN_BIN="${MVN_BIN:-mvn}"

command -v "$PYTHON_BIN" >/dev/null
command -v "$MVN_BIN" >/dev/null

echo "[verify] checking shell script syntax"
while IFS= read -r -d '' script; do
  bash -n "$script"
done < <(find "$ROOT_DIR/scripts" -type f -name '*.sh' -print0)

echo "[verify] running Java unit tests"
(
  cd "$ROOT_DIR/enterprise-rag-backend"
  "$MVN_BIN" -q test
)

echo "[verify] running Python core unit tests"
(
  cd "$ROOT_DIR/rag-ai-service"
  MINIO_ACCESS_KEY="unit-test-access-key" \
  MINIO_SECRET_KEY="unit-test-secret-key" \
  PYTHONDONTWRITEBYTECODE=1 \
    "$PYTHON_BIN" -m unittest discover -s tests -p 'test_*.py' -v
)

echo "[verify] all checks passed"
