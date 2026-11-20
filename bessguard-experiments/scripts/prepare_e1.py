#!/usr/bin/env python3
"""Prepare unique Fabric-backed battery fixtures and JMeter rows for E1."""
from __future__ import annotations

import argparse
import csv
import os
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from common import Api, create_battery, create_bess, dispatch_payload, timing, write_json


def main():
    parser = argparse.ArgumentParser(description="E1: create fresh batteries for paired 8/6-kW competing reservations")
    parser.add_argument("--api", default=os.getenv("BESSGUARD_BACKEND_URL", "http://localhost:8081"))
    parser.add_argument("--pairs", type=int, default=20)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    if args.pairs < 1 or args.pairs > 300:
        parser.error("--pairs must be between 1 and 300")

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    api = Api(args.api)
    for role in ("admin", "certifier", "gateway", "ems"):
        api.login(role)

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:7]
    bess_id = "e1-bess-" + run_id
    create_bess(api, bess_id)
    # Scheduled well into the future so experiments cannot accidentally trigger BMS discharge.
    window = timing(offset_min=120, duration_min=15)
    pairs = []
    rows = []
    began = time.monotonic()
    for i in range(args.pairs):
        pair_id = f"e1-{run_id}-{i + 1:03d}"
        battery_id = "bat-" + pair_id
        cmd_a = "cmd-a-" + pair_id
        cmd_b = "cmd-b-" + pair_id
        create_battery(api, bess_id, battery_id)
        pair = {
            "pairId": pair_id, "batteryId": battery_id,
            "commands": [cmd_a, cmd_b], "powersKw": [8, 6]
        }
        pairs.append(pair)
        for command_id, power in zip(pair["commands"], pair["powersKw"]):
            dispatch = dispatch_payload(command_id, battery_id, power, 1, window)
            rows.append({
                "pairId": pair_id, "batteryId": battery_id, "commandId": command_id,
                "requestedPowerKw": str(power), "startTime": dispatch["startTime"],
                "endTime": dispatch["endTime"], "expiresAt": dispatch["expiresAt"]
            })
        print(f"[{i+1}/{args.pairs}] ACTIVE {battery_id}", flush=True)

    fixture = {
        "experiment": "E1-concurrent-reservations", "runId": run_id,
        "api": args.api, "bessId": bess_id,
        "pairCount": args.pairs, "window": list(window),
        "fixtureSetupSeconds": round(time.monotonic() - began, 3),
        "pairs": pairs,
    }
    write_json(output / "fixture-e1.json", fixture)
    csv_path = output / "requests-e1.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=[
            "pairId", "batteryId", "commandId", "requestedPowerKw", "startTime", "endTime", "expiresAt"
        ])
        writer.writeheader()
        writer.writerows(rows)
    print(f"Prepared {args.pairs} independent pairs, {len(rows)} JMeter requests.")
    print(f"fixture={output / 'fixture-e1.json'}")
    print(f"csv={csv_path}")


if __name__ == "__main__":
    main()
