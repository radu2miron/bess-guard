#!/usr/bin/env python3
"""E2: stale (optionally late-redelivered) v1 command after v2 recertification."""
from __future__ import annotations

import argparse
import base64
import csv
import json
import os
import time
import uuid
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from common import Api, create_battery, create_bess, dispatch_payload, initial_certificate, timing, write_json


def replay_late_broker_message(management_url: str, rabbit_user: str, rabbit_password: str,
                               command_id: str, battery_id: str):
    """Send a real RabbitMQ message only AFTER v2 is active, using Management API."""
    message = {"commandId": command_id, "batteryId": battery_id,
               "publishedAt": int(time.time() * 1000)}
    envelope = {
        "properties": {
            "content_type": "application/json",
            "headers": {"__TypeId__": "edu.tucn.bessguard.model.DispatchMessage"},
        },
        "routing_key": "bessguard-dispatch-queue",
        "payload": json.dumps(message, separators=(",", ":")),
        "payload_encoding": "string",
    }
    auth = base64.b64encode(f"{rabbit_user}:{rabbit_password}".encode("utf-8")).decode("ascii")
    request = urllib.request.Request(management_url.rstrip("/") + "/api/exchanges/%2F/amq.default/publish",
                                     method="POST", data=json.dumps(envelope).encode("utf-8"),
                                     headers={"Authorization": "Basic " + auth,
                                              "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            receipt = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        reason = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"RabbitMQ Management HTTP {exc.code}: {reason[:300]}") from exc
    if receipt.get("routed") is not True:
        raise RuntimeError(f"Late RabbitMQ message was not routed to dispatch queue: {receipt}")


def test_one(api: Api, bess_id: str, n: int, run_id: str, use_rabbit: bool,
             mgmt: str, rabbit_user: str, rabbit_password: str, poll_timeout: int):
    battery_id = f"e2-bat-{run_id}-{n:03d}"
    command_old = f"e2-v1-{run_id}-{n:03d}"
    command_new = f"e2-v2-{run_id}-{n:03d}"
    window = timing(offset_min=120, duration_min=15)
    create_battery(api, bess_id, battery_id)

    # v1, 9 kW accepted under a 10-kW certified limit.
    reserved = api.call("POST", "/api/dispatch", dispatch_payload(command_old, battery_id, 9, 1, window), role="ems")
    assert reserved["reservation"]["status"] == "RESERVED", reserved
    assert reserved["deliveryState"] == "QUEUED", reserved
    api.delivery_wait(command_old, "STAGED", timeout_seconds=poll_timeout)
    initial_local = api.call("GET", f"/api/gateway/batteries/{battery_id}", role="gateway")
    initial_energy = float(initial_local["availableEnergyKwh"])

    # Certifier closes admission. Gateway acknowledges quiescence; chaincode cancels v1 reservation.
    certificate = api.call("POST", "/api/certificates/recertification",
                           initial_certificate(battery_id, 2, 6), role="certifier")
    assert certificate["version"] == 2, certificate
    quiescing = api.call("POST", f"/api/gateway/batteries/{battery_id}/quiescence/ack", role="gateway")
    assert quiescing["mode"] == "QUIESCING", quiescing
    updated = api.call("POST", f"/api/certificates/{battery_id}/activate", role="certifier")
    assert updated["activeCertificateVersion"] == 2, updated
    active = api.call("POST", f"/api/gateway/batteries/{battery_id}/activate",
                      {"version": 2, "availableEnergyKwh": initial_energy}, role="gateway")
    assert active["mode"] == "ACTIVE", active

    # If requested, inject a genuine delayed Rabbit message after v2 is ACTIVE.
    if use_rabbit:
        replay_late_broker_message(mgmt, rabbit_user, rabbit_password, command_old, battery_id)
        api.delivery_wait(command_old, "IGNORED", timeout_seconds=poll_timeout)

    # Attempt execution of the previously STAGED old command. It must not actuate the simulated BMS.
    decision = api.call("POST", f"/api/gateway/dispatch/{command_old}/execute", role="gateway")
    old_committed = api.call("GET", f"/api/dispatch/{command_old}", role="ems")
    state = api.call("GET", f"/api/batteries/{battery_id}/state", role="ems")
    after = api.call("GET", f"/api/gateway/batteries/{battery_id}", role="gateway")
    after_energy = float(after["availableEnergyKwh"])
    assert decision["decision"] == "REJECTED", decision
    assert old_committed["status"] == "CANCELLED", old_committed
    assert state["mode"] == "ACTIVE" and state["activeCertificateVersion"] == 2, state
    assert after["active"] is True and after["version"] == 2, after
    assert abs(initial_energy - after_energy) < 1e-9, (initial_energy, after_energy)

    # Availability: valid new-version command can still be reserved and delivered.
    new_reserved = api.call("POST", "/api/dispatch",
                            dispatch_payload(command_new, battery_id, 5, 2, window), role="ems")
    assert new_reserved["reservation"]["status"] == "RESERVED", new_reserved
    assert new_reserved["deliveryState"] == "QUEUED", new_reserved
    api.delivery_wait(command_new, "STAGED", timeout_seconds=poll_timeout)

    return {
        "run": n, "batteryId": battery_id,
        "oldCommandId": command_old, "oldCertificateVersion": 1,
        "newCertificateVersion": state["activeCertificateVersion"],
        "oldCommandLedgerStatus": old_committed["status"],
        "gatewayDecision": decision["decision"],
        "simulatedEnergyBeforeKwh": initial_energy,
        "simulatedEnergyAfterKwh": after_energy,
        "lateRabbitMessageInjected": use_rabbit,
        "lateRabbitMessageIgnored": use_rabbit,
        "newV2CommandReservedAndStaged": True,
        "pass": True,
    }


def main():
    parser = argparse.ArgumentParser(description="E2: recertification must fence old dispatch commands")
    parser.add_argument("--api", default=os.getenv("BESSGUARD_BACKEND_URL", "http://localhost:8081"))
    parser.add_argument("--repetitions", type=int, default=10)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--late-rabbit", action="store_true",
                        help="Inject old command via RabbitMQ Management HTTP after v2 activation")
    parser.add_argument("--rabbit-management-url", default=os.getenv("BESSGUARD_RABBIT_MANAGEMENT_URL", "http://localhost:15672"))
    parser.add_argument("--poll-timeout", type=int, default=45)
    args = parser.parse_args()
    if args.repetitions < 1 or args.repetitions > 300:
        parser.error("--repetitions must be between 1 and 300")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    api = Api(args.api)
    for role in ("admin", "certifier", "ems", "gateway"):
        api.login(role)

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:7]
    bess_id = "e2-bess-" + run_id
    create_bess(api, bess_id)
    rabbit_user = os.getenv("BESSGUARD_RABBIT_USER", "guest")
    rabbit_password = os.getenv("BESSGUARD_RABBIT_PASSWORD", "guest")
    results = []
    began = time.monotonic()

    for i in range(1, args.repetitions + 1):
        try:
            row = test_one(api, bess_id, i, run_id, args.late_rabbit,
                           args.rabbit_management_url, rabbit_user, rabbit_password, args.poll_timeout)
            print(f"[{i}/{args.repetitions}] PASS: v1 cancelled and rejected; unchanged energy; v2 admitted", flush=True)
        except Exception as exc:
            row = {"run": i, "pass": False, "error": str(exc)}
            print(f"[{i}/{args.repetitions}] FAIL: {exc}", flush=True)
        results.append(row)

    successful = sum(1 for r in results if r["pass"])
    summary = {
        "experiment": "E2-stale-command-after-recertification",
        "runId": run_id,
        "scenario": ("queued and staged old command, plus actual late RabbitMQ replay after v2"
                     if args.late_rabbit else "queued and staged old command, execute attempt after v2"),
        "runs": args.repetitions,
        "passedRuns": successful,
        "violationsOrErrors": args.repetitions - successful,
        "lateRabbitDeliveryTested": args.late_rabbit,
        "durationSeconds": round(time.monotonic() - began, 2),
        "checks": results,
        "passed": successful == args.repetitions,
    }
    write_json(output / "summary-e2.json", summary)
    with (output / "results-e2.csv").open("w", encoding="utf-8", newline="") as stream:
        fields = ["run", "pass", "batteryId", "oldCommandId", "oldCertificateVersion",
                  "newCertificateVersion", "oldCommandLedgerStatus", "gatewayDecision",
                  "simulatedEnergyBeforeKwh", "simulatedEnergyAfterKwh",
                  "lateRabbitMessageInjected", "lateRabbitMessageIgnored", "newV2CommandReservedAndStaged", "error"]
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(results)
    print(f"E2: {successful}/{args.repetitions} tests passed; late Rabbit replay: {args.late_rabbit}")
    print("JSON:", output / "summary-e2.json")
    print("CSV:", output / "results-e2.csv")
    if not summary["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
