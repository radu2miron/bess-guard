#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
API="${BESSGUARD_BACKEND_URL:-http://localhost:8081}"
REPETITIONS="${E2_RUNS:-10}"
RUN_DIR="${1:-$ROOT/results/e2-$(date -u +%Y%m%dT%H%M%SZ)-$$}"

mkdir -p "$RUN_DIR"
ARGS=(--api "$API" --repetitions "$REPETITIONS" --output-dir "$RUN_DIR")

if [[ "${E2_LATE_RABBIT:-0}" == "1" ]]; then
    ARGS+=(--late-rabbit)
fi

python3 "$ROOT/scripts/e2_recertification.py" "${ARGS[@]}"
