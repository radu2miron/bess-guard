#!/usr/bin/env python3
"""Independently validate the committed ledger state after JMeter concurrency E1."""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import statistics
from collections import Counter
from pathlib import Path

from common import Api, write_json

LABEL = re.compile(r"^E1 ([^ ]+) ([^ ]+)$")


def percentile(values, pct):
    if not values:
        return None
    ordered = sorted(values)
    import math
    return ordered[max(0, min(len(ordered) - 1, math.ceil(len(ordered) * pct / 100.0) - 1))]


def main():
    parser = argparse.ArgumentParser(description="Verify no conflicting E1 reservation pairs committed")
    parser.add_argument("--fixture", required=True, type=Path)
    parser.add_argument("--jtl", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--max-skew-ms", type=int, default=2000)
    args = parser.parse_args()
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    api = Api(fixture["api"])
    api.login("ems")
    pairs = fixture["pairs"]
    jmeter = {}
    with args.jtl.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        for row in reader:
            name = row.get("label", "")
            match = LABEL.fullmatch(name)
            if match is None:
                continue
            pair_id, command_id = match.groups()
            jmeter.setdefault(pair_id, {})[command_id] = {
                "httpCode": row.get("responseCode"),
                "startMs": int(row["timeStamp"]),
                "elapsedMs": int(row["elapsed"]),
            }

    dispatches = api.call("GET", "/api/dispatch", role="ems")
    if not isinstance(dispatches, list):
        raise RuntimeError(f"Expected JSON array from GET /api/dispatch, got {type(dispatches)}")
    by_id = {item.get("id"): item for item in dispatches if isinstance(item, dict)}

    checks = []
    codes = Counter()
    success_durations = []
    violating_pairs = 0
    inconclusive_pairs = 0
    for pair in pairs:
        pair_id = pair["pairId"]
        ids = pair["commands"]
        responses = jmeter.get(pair_id, {})
        for cmd_id in ids:
            code = responses.get(cmd_id, {}).get("httpCode", "MISSING")
            codes[str(code)] += 1
        seen = [by_id[id_] for id_ in ids if id_ in by_id]
        reserved = [cmd for cmd in seen if cmd.get("status") == "RESERVED"]
        state = api.call("GET", f"/api/batteries/{pair['batteryId']}/state", role="ems")
        open_ids = state.get("openReservationIds", [])
        confirmed = [r["id"] for r in reserved]
        success_samples = [responses[cid] for cid in ids
                           if cid in responses and str(responses[cid]["httpCode"]).startswith("2")]
        success_http = len(success_samples)
        for r in success_samples:
            success_durations.append(r["elapsedMs"])
        start_skew = (abs(responses[ids[0]]["startMs"] - responses[ids[1]]["startMs"])
                      if all(i in responses for i in ids) else None)

        # At most one is the safety invariant; exactly one also establishes progress.
        safety_violation = len(reserved) > 1 or len([id_ for id_ in ids if id_ in open_ids]) > 1
        progress_failure = len(reserved) != 1 or success_http != 1 or set(confirmed) != set(open_ids)
        not_concurrent = start_skew is None or start_skew > args.max_skew_ms
        failed = safety_violation or progress_failure
        if safety_violation:
            violating_pairs += 1
        if failed or not_concurrent:
            inconclusive_pairs += 1

        checks.append({
            "pairId": pair_id, "commands": ids,
            "httpCodes": {cid: responses.get(cid, {}).get("httpCode", "MISSING") for cid in ids},
            "committedReservedIds": confirmed,
            "ledgerOpenReservationIds": open_ids,
            "startSkewMs": start_skew,
            "safetyViolation": safety_violation,
            "exactlyOneCommitted": len(reserved) == 1,
            "exactlyOneHttpSuccess": success_http == 1,
            "validConcurrentPair": not (failed or not_concurrent),
        })

    summary = {
        "experiment": "E1-concurrent-reservations",
        "pairsPlanned": len(pairs), "pairsVerified": len(checks),
        "validConcurrentPairs": len(pairs) - inconclusive_pairs,
        "safetyViolations": violating_pairs,
        "failedOrInconclusivePairs": inconclusive_pairs,
        "startSkewToleranceMs": args.max_skew_ms,
        "maxObservedStartSkewMs": max((c["startSkewMs"] for c in checks
                                        if c["startSkewMs"] is not None), default=None),
        "httpStatusCounts": dict(codes),
        "successfulRequests": len(success_durations),
        "successfulRequestMeanLatencyMs": round(statistics.mean(success_durations), 2) if success_durations else None,
        "successfulRequestP95LatencyMs": percentile(success_durations, 95),
        "passed": inconclusive_pairs == 0 and len(checks) == len(pairs),
        "checks": checks,
    }
    write_json(args.output, summary)
    print("E1: %d/%d valid simultaneous reservation pairs; invariant violations=%d; inconclusive=%d" %
          (summary["validConcurrentPairs"], len(pairs), violating_pairs, inconclusive_pairs))
    print("JMeter HTTP status codes:", dict(codes), "(one deliberate rejection per pair is expected)")
    print("Mean successful reservation latency:", summary["successfulRequestMeanLatencyMs"], "ms")
    print("p95 successful reservation latency:", summary["successfulRequestP95LatencyMs"], "ms")
    print("Output:", args.output)
    if not summary["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
