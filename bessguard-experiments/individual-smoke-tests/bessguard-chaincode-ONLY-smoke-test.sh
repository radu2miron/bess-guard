#!/usr/bin/env bash
# BESSGuard chaincode smoke test: run from fabric-samples/test-network AFTER lifecycle commit.
# Creates uniquely named test records; does not reset the network.
set -euo pipefail

: "${PWD:?}"
if [[ ! -d organizations/peerOrganizations/org1.example.com ]]; then
  echo "ERROR: run this script from fabric/test-network (where ./network.sh exists)." >&2
  exit 1
fi
command -v python3 >/dev/null || { echo "ERROR: python3 is required." >&2; exit 1; }
command -v peer >/dev/null || { echo "ERROR: peer CLI missing; export PATH and FABRIC_CFG_PATH as in README." >&2; exit 1; }

export CORE_PEER_TLS_ENABLED=true
export CORE_PEER_LOCALMSPID="Org1MSP"
export CORE_PEER_MSPCONFIGPATH="${PWD}/organizations/peerOrganizations/org1.example.com/users/User1@org1.example.com/msp"
export CORE_PEER_TLS_ROOTCERT_FILE="${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt"
export CORE_PEER_ADDRESS=localhost:7051

CHANNEL="${CHANNEL:-bessguard-channel}"
CHAINCODE="${CHAINCODE:-bessguard-chaincode}"
ORDERER_TLS="${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem"
PEER1_TLS="${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt"
PEER2_TLS="${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt"

for required in "$ORDERER_TLS" "$PEER1_TLS" "$PEER2_TLS" "$CORE_PEER_MSPCONFIGPATH"; do
  [[ -e "$required" ]] || { echo "ERROR: missing $required" >&2; exit 1; }
done

function ctor() {
  # Build Fabric JSON -c payload from function and string arguments, with correct JSON escaping.
  python3 -c 'import json,sys; print(json.dumps({"function":sys.argv[1],"Args":sys.argv[2:]}))' "$@"
}

function invoke() {
  local payload
  payload=$(ctor "$@")
  peer chaincode invoke \
    --waitForEvent --waitForEventTimeout 60s \
    -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com \
    --tls --cafile "$ORDERER_TLS" \
    -C "$CHANNEL" -n "$CHAINCODE" \
    --peerAddresses localhost:7051 --tlsRootCertFiles "$PEER1_TLS" \
    --peerAddresses localhost:9051 --tlsRootCertFiles "$PEER2_TLS" \
    -c "$payload"
}

function query() {
  local payload
  payload=$(ctor "$@")
  peer chaincode query -C "$CHANNEL" -n "$CHAINCODE" -c "$payload"
}

function assert_field() {
  local function="$1" identifier="$2" field="$3" expected="$4" result
  result=$(query "$function" "$identifier")
  echo "$result"
  printf '%s' "$result" | python3 -c '
import json, sys
key, expected = sys.argv[1], sys.argv[2]
data = json.load(sys.stdin)
actual = str(data.get(key, ""))
if actual != expected:
    print(f"ASSERTION FAILED: {key}={actual!r}, expected={expected!r}", file=sys.stderr)
    sys.exit(1)
' "$field" "$expected"
}

function expect_failure() {
  local description="$1" expected_fragment="$2" rejected_id="$3"; shift 3
  local output records
  echo -e "\n[EXPECTED FAILURE] $description"
  if output=$(invoke "$@" 2>&1); then
    echo "ERROR: command unexpectedly succeeded: $description" >&2
    exit 1
  fi
  echo "$output"
  if [[ "$output" == *"$expected_fragment"* ]]; then
    echo "INFO: exact application validation message was returned."
  elif [[ "$output" == *'response: status:500 message:"Error during contract method execution"'* ]]; then
    echo "INFO: Fabric returned generic Java exception; checking ledger to confirm no command was stored."
    echo "WARNING: generic status 500 does NOT independently prove the expected validation reason."
  else
    echo "ERROR: unexpected failure response; not accepting it as a valid negative test." >&2
    exit 1
  fi

  # The Java contract runtime may mask IllegalArgumentException messages as a generic 500.
  # A rejected reservation must also be absent from ledger state. readDispatch() on a missing
  # key also returns a generic 500, so check the collection using read-only query instead.
  if ! records=$(query getAllDispatchCommands 2>&1); then
    echo "ERROR: unable to query dispatch records after failed invoke: $records" >&2
    exit 1
  fi
  printf '%s' "$records" | python3 -c '
import json, sys
rejected_id = sys.argv[1]
records = json.load(sys.stdin)
if not isinstance(records, list):
    raise SystemExit("ERROR: getAllDispatchCommands did not return a JSON array.")
if any(isinstance(item, dict) and item.get("id") == rejected_id for item in records):
    raise SystemExit("ERROR: rejected dispatch was found in ledger: " + rejected_id)
print("PASS: transaction rejected and dispatch is absent from ledger: " + rejected_id)
' "$rejected_id"
}

ID="smoke-$(date -u +%Y%m%d%H%M%S)-$$"
BESS_ID="bess-$ID"
BAT_ID="bat-$ID"
CMD1="dispatch-ok-$ID"
NOW=$(date -u +%s)
START=$(date -u -d "@$((NOW + 86400))" +%Y-%m-%dT%H:%M:%SZ)
END=$(date -u -d "@$((NOW + 87300))" +%Y-%m-%dT%H:%M:%SZ)
EXPIRES=$(date -u -d "@$((NOW + 87360))" +%Y-%m-%dT%H:%M:%SZ)

function dispatch_json() {
  local id="$1" version="$2" power="$3"
  printf '{"docType":"DISPATCH_COMMAND","id":"%s","batteryId":"%s","certificateVersion":%s,"startTime":"%s","endTime":"%s","expiresAt":"%s","requestedPowerKw":%s}' \
    "$id" "$BAT_ID" "$version" "$START" "$END" "$EXPIRES" "$power"
}

echo "BESSGuard smoke test; channel=$CHANNEL chaincode=$CHAINCODE"
echo "Fabric caller MSP=Org1MSP, certificate=User1@org1.example.com"
echo "Test record suffix: $ID"
echo "Dispatch window: $START -> $END"

echo -e '\n[1] Register second-life BESS'
invoke registerBess "{\"docType\":\"SECOND_LIFE_BESS\",\"id\":\"$BESS_ID\",\"name\":\"BESSGuard smoke test\"}"
assert_field readBess "$BESS_ID" id "$BESS_ID"

echo -e '\n[2] Register battery in BESS'
invoke registerBattery "{\"docType\":\"BATTERY_UNIT\",\"id\":\"$BAT_ID\",\"bessId\":\"$BESS_ID\",\"serialNumber\":\"SN-$ID\",\"nominalCapacityKwh\":20}"
assert_field readBatteryCoordinationState "$BAT_ID" mode SUSPENDED

echo -e '\n[3] Issue initial operating certificate v1 (10 kW)'
invoke issueInitialCertificate "{\"docType\":\"OPERATING_CERTIFICATE\",\"batteryId\":\"$BAT_ID\",\"version\":1,\"maxDischargePowerKw\":10,\"minEnergyKwh\":2,\"maxEnergyKwh\":20,\"validFrom\":\"2025-01-01T00:00:00Z\",\"validUntil\":\"2035-01-01T00:00:00Z\",\"evidenceRef\":\"inspection-001\"}"
assert_field readBatteryCoordinationState "$BAT_ID" mode UPDATED

echo -e '\n[4] Acknowledge gateway version v1 with 15 kWh available'
# Prototype chaincode assumes the gateway has already installed v1 locally.
invoke activateGatewayVersion "$BAT_ID" 1 15
assert_field readBatteryCoordinationState "$BAT_ID" mode ACTIVE

echo -e '\n[5] Reserve a valid 8 kW, 15-minute command'
invoke reserveDispatch "$(dispatch_json "$CMD1" 1 8)"
assert_field readDispatch "$CMD1" status RESERVED

echo -e '\n[6] Reject a 12 kW request (> 10 kW certificate)'
expect_failure 'Power exceeds certified limit' 'Requested power exceeds certified maximum.' "dispatch-power-$ID" reserveDispatch "$(dispatch_json "dispatch-power-$ID" 1 12)"

echo -e '\n[7] Reject overlapping 6 kW request (8+6 > 10 kW)'
expect_failure 'Overlapping reservations exceed battery power' 'Concurrent reservations exceed certified discharge power.' "dispatch-overlap-$ID" reserveDispatch "$(dispatch_json "dispatch-overlap-$ID" 1 6)"

echo -e '\n[8] Begin recertification: new max 6 kW'
invoke beginRecertification "{\"docType\":\"OPERATING_CERTIFICATE\",\"batteryId\":\"$BAT_ID\",\"version\":2,\"maxDischargePowerKw\":6,\"minEnergyKwh\":2,\"maxEnergyKwh\":20,\"validFrom\":\"2025-01-01T00:00:00Z\",\"validUntil\":\"2035-01-01T00:00:00Z\",\"evidenceRef\":\"inspection-002\"}"
assert_field readBatteryCoordinationState "$BAT_ID" mode QUIESCING
# Local gateway must block old commands BEFORE acknowledging quiescence.
invoke acknowledgeQuiescence "$BAT_ID"
assert_field readDispatch "$CMD1" status CANCELLED
invoke activateCertificate "$BAT_ID"
assert_field readBatteryCoordinationState "$BAT_ID" mode UPDATED
# Local gateway must have installed v2 before this Fabric transaction.
invoke activateGatewayVersion "$BAT_ID" 2 15
assert_field readBatteryCoordinationState "$BAT_ID" mode ACTIVE

echo -e '\n[9] Reject a stale v1 dispatch after activation of v2'
expect_failure 'Old certificate version 1 is stale' 'Battery is not ACTIVE or certificate version is stale.' "dispatch-stale-$ID" reserveDispatch "$(dispatch_json "dispatch-stale-$ID" 1 4)"

echo -e '\n[10] Accept 5 kW under new version 2 (max 6 kW)'
invoke reserveDispatch "$(dispatch_json "dispatch-v2-$ID" 2 5)"
assert_field readDispatch "dispatch-v2-$ID" status RESERVED

echo -e "\nSUCCESS: 10 checks passed. BESS=$BESS_ID BATTERY=$BAT_ID"
echo "Note: no physical BMS or gateway was exercised; only ledger transitions were tested."
