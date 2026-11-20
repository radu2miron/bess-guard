#!/usr/bin/env bash
set -euo pipefail

API="${BESSGUARD_BACKEND_URL:-http://localhost:8081}"
ADMIN_USER="${BESSGUARD_ADMIN_USERNAME:-user1}"
ADMIN_PASSWORD="${BESSGUARD_ADMIN_PASSWORD:-pwd1}"
CERTIFIER_PASSWORD="${BESSGUARD_CERTIFIER_PASSWORD:-pwd1}"
EMS_PASSWORD="${BESSGUARD_EMS_PASSWORD:-pwd1}"
GATEWAY_PASSWORD="${BESSGUARD_GATEWAY_PASSWORD:-pwd1}"

command -v curl >/dev/null || { echo 'curl is required'; exit 1; }
command -v jq >/dev/null || { echo 'jq is required'; exit 1; }

login() {
    local username="$1" password="$2" response
    response="$(curl --fail-with-body -sS -X POST "$API/login" \
        -H 'Content-Type: application/json' \
        --data "$(jq -n --arg u "$username" --arg p "$password" '{username:$u,password:$p}')")"
    echo "$response" | jq -er '.token'
}

call_api() {
    local verb="$1" token="$2" path="$3" payload="${4:-}"
    local opts=(-sS --fail-with-body -X "$verb" "$API$path" -H "Authorization: Bearer $token")

    if [[ -n "$payload" ]]; then
        opts+=(-H 'Content-Type: application/json' --data "$payload")
    fi

    curl "${opts[@]}"
}

ADMIN_TOKEN="$(login "$ADMIN_USER" "$ADMIN_PASSWORD")"
CERTIFIER_TOKEN="$(login certifier "$CERTIFIER_PASSWORD")"
EMS_TOKEN="$(login ems "$EMS_PASSWORD")"
GATEWAY_TOKEN="$(login gateway "$GATEWAY_PASSWORD")"

SUFFIX="$(date -u +%Y%m%d%H%M%S)-$$"
BESS="bess-http-$SUFFIX"
BAT="battery-http-$SUFFIX"
CMD="dispatch-http-$SUFFIX"
START="$(date -u -d '+10 minutes' +'%Y-%m-%dT%H:%M:%SZ')"
END="$(date -u -d '+25 minutes' +'%Y-%m-%dT%H:%M:%SZ')"
EXP="$(date -u -d '+26 minutes' +'%Y-%m-%dT%H:%M:%SZ')"

printf '\n[1] Register BESS\n'
call_api POST "$ADMIN_TOKEN" /api/bess "$(jq -n --arg id "$BESS" '{id:$id,name:"BESSGuard HTTP test"}')" \
    | jq -e --arg id "$BESS" '.id == $id' >/dev/null

printf '[2] Register battery\n'
call_api POST "$ADMIN_TOKEN" /api/batteries \
    "$(jq -n --arg id "$BAT" --arg bess "$BESS" '{id:$id,bessId:$bess,serialNumber:$id,nominalCapacityKwh:20}')" \
    | jq -e --arg id "$BAT" '.id == $id' >/dev/null

printf '[3] Issue v1 certificate (10 kW)\n'
CERT1="$(jq -n --arg id "$BAT" '{batteryId:$id,version:1,maxDischargePowerKw:10,minEnergyKwh:2,maxEnergyKwh:20,validFrom:"2025-01-01T00:00:00Z",validUntil:"2035-01-01T00:00:00Z",evidenceRef:"http-inspection-v1"}')"
call_api POST "$CERTIFIER_TOKEN" /api/certificates/initial "$CERT1" | jq -e '.version == 1' >/dev/null

printf '[4] Activate gateway v1\n'
call_api POST "$GATEWAY_TOKEN" "/api/gateway/batteries/$BAT/activate" \
    '{"version":1,"availableEnergyKwh":15}' | jq -e '.mode == "ACTIVE"' >/dev/null

printf '[5] EMS reserve dispatch 8 kW, only then publish to RabbitMQ\n'
COMMAND="$(jq -n --arg id "$CMD" --arg bat "$BAT" --arg start "$START" --arg end "$END" --arg exp "$EXP" '{id:$id,batteryId:$bat,certificateVersion:1,startTime:$start,endTime:$end,expiresAt:$exp,requestedPowerKw:8}')"
call_api POST "$EMS_TOKEN" /api/dispatch "$COMMAND" \
    | jq -e '.reservation.status == "RESERVED" and .deliveryState == "QUEUED"' >/dev/null

printf '[6] Wait for queued command to reach gateway\n'
STAGED=0
for _ in $(seq 1 30); do
    STATE="$(call_api GET "$EMS_TOKEN" "/api/dispatch/$CMD/delivery" | jq -r '.state')"

    if [[ "$STATE" == "STAGED" ]]; then
        STAGED=1
        break
    fi

    sleep 1
done

if [[ "$STAGED" != 1 ]]; then
    echo "ERROR: queue delivery was not confirmed as STAGED (last state: $STATE)" >&2
    exit 1
fi

printf '[7] Verify committed reservation\n'
call_api GET "$EMS_TOKEN" "/api/dispatch/$CMD" | jq -e '.status == "RESERVED"' >/dev/null

printf '[8] Reject over-limit reservation 12 kW\n'
BAD="$(jq -n --arg id "invalid-$SUFFIX" --arg bat "$BAT" --arg start "$START" --arg end "$END" --arg exp "$EXP" '{id:$id,batteryId:$bat,certificateVersion:1,startTime:$start,endTime:$end,expiresAt:$exp,requestedPowerKw:12}')"
HTTP_CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$API/api/dispatch" \
    -H "Authorization: Bearer $EMS_TOKEN" -H 'Content-Type: application/json' --data "$BAD")"

if [[ "$HTTP_CODE" -lt 400 ]]; then
    echo 'ERROR: over-limit reservation was not rejected' >&2
    exit 1
fi

printf '[9] Begin recertification v2 (6 kW)\n'
CERT2="$(jq -n --arg id "$BAT" '{batteryId:$id,version:2,maxDischargePowerKw:6,minEnergyKwh:2,maxEnergyKwh:20,validFrom:"2025-01-01T00:00:00Z",validUntil:"2035-01-01T00:00:00Z",evidenceRef:"http-inspection-v2"}')"
call_api POST "$CERTIFIER_TOKEN" /api/certificates/recertification "$CERT2" | jq -e '.version == 2' >/dev/null

printf '[10] Acknowledge quiescence, then activate certificate and gateway\n'
call_api POST "$GATEWAY_TOKEN" "/api/gateway/batteries/$BAT/quiescence/ack" | jq -e '.mode == "QUIESCING"' >/dev/null
call_api POST "$CERTIFIER_TOKEN" "/api/certificates/$BAT/activate" | jq -e '.activeCertificateVersion == 2' >/dev/null
call_api POST "$GATEWAY_TOKEN" "/api/gateway/batteries/$BAT/activate" \
    '{"version":2,"availableEnergyKwh":15}' | jq -e '.mode == "ACTIVE"' >/dev/null

printf '[11] Old v1 command must be cancelled on ledger\n'
call_api GET "$EMS_TOKEN" "/api/dispatch/$CMD" | jq -e '.status == "CANCELLED"' >/dev/null

printf '\nSUCCESS: 11 HTTP checks passed. BESS=%s BATTERY=%s\n' "$BESS" "$BAT"
