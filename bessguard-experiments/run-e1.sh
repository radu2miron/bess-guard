#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
API="${BESSGUARD_BACKEND_URL:-http://localhost:8081}"
PAIRS="${E1_PAIRS:-20}"
RUN_DIR="${1:-$ROOT/results/e1-$(date -u +%Y%m%dT%H%M%SZ)-$$}"

for executable in python3 jmeter; do
    if ! command -v "$executable" >/dev/null 2>&1; then
        echo "Required executable not found: $executable" >&2
        exit 1
    fi
done

mkdir -p "$RUN_DIR"
RUN_DIR="$(cd "$RUN_DIR" && pwd)"

python3 "$ROOT/scripts/prepare_e1.py" --api "$API" --pairs "$PAIRS" --output-dir "$RUN_DIR"

# JMeter credentials are short-lived JWTs; no passwords or token are written into fixture files.
EMS_TOKEN="$(PYTHONPATH="$ROOT/scripts" python3 - <<'PY'
import os
from common import Api
api = Api(os.getenv('BESSGUARD_BACKEND_URL', 'http://localhost:8081'))
print(api.login('ems'))
PY
)"

# Read host, protocol and port from the base URL (local http(s) only).
read -r PROTOCOL HOST PORT < <(python3 - "$API" <<'PY'
from urllib.parse import urlparse
import sys
parsed = urlparse(sys.argv[1])
if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.path not in ('', '/'):
    raise SystemExit('Only a base API URL such as http://localhost:8081 is supported')
print(parsed.scheme, parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80))
PY
)

printf 'Running E1 with exactly 2 synchronized JMeter threads, %s pairs.\n' "$PAIRS"
# JMeter -JjwtToken appears in local process arguments while running; use only on a controlled test machine.
jmeter -n -t "$ROOT/jmeter/e1_concurrent_reservations.jmx" -l "$RUN_DIR/results-e1.jtl" \
    -Jpairs="$PAIRS" -JcsvFile="$RUN_DIR/requests-e1.csv" \
    -Jprotocol="$PROTOCOL" -Jhost="$HOST" -Jport="$PORT" \
    -JjwtToken="$EMS_TOKEN" -Jjmeter.save.saveservice.output_format=csv \
    -Jjmeter.save.saveservice.print_field_names=true

unset EMS_TOKEN
python3 "$ROOT/scripts/verify_e1.py" --fixture "$RUN_DIR/fixture-e1.json" \
    --jtl "$RUN_DIR/results-e1.jtl" --output "$RUN_DIR/summary-e1.json"
