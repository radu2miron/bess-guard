#!/usr/bin/env python3
"""Shared REST helpers for reproducible BESSGuard prototype experiments."""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path


class ApiError(RuntimeError):
    def __init__(self, method: str, path: str, code: int, body: str):
        self.method = method
        self.path = path
        self.code = code
        self.body = body
        super().__init__(f"{method} {path}: HTTP {code}; {body[:280]}")


class Api:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.tokens = {}

    def call(self, method: str, path: str, payload=None, role: str | None = None, timeout: int = 120):
        headers = {"Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if role:
            headers["Authorization"] = "Bearer " + self.tokens[role]
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise ApiError(method, path, exc.code, body) from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"{method} {path}: cannot connect to {self.base_url}: {exc}") from exc
        if not body:
            return None
        try:
            return json.loads(body)
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"{method} {path}: expected JSON, got: {body[:250]}") from exc

    def login(self, role: str):
        if role == "admin":
            username = os.environ.get("BESSGUARD_ADMIN_USERNAME", "user1")
            password = os.environ.get("BESSGUARD_ADMIN_PASSWORD", "pwd1")
        else:
            username = role
            variable = f"BESSGUARD_{role.upper()}_PASSWORD"
            password = os.environ.get(variable)
            if not password:
                raise RuntimeError(f"Set the {variable} environment variable for JWT login.")
        result = self.call("POST", "/login", {"username": username, "password": password})
        token = result.get("token") if isinstance(result, dict) else None
        if not token:
            raise RuntimeError(f"No JWT token was returned for role {role}.")
        self.tokens[role] = token
        return token

    def delivery_wait(self, command_id: str, expected: str, timeout_seconds: int = 45):
        deadline = time.monotonic() + timeout_seconds
        observed = None
        while time.monotonic() < deadline:
            state = self.call("GET", f"/api/dispatch/{command_id}/delivery", role="ems")
            observed = state.get("state")
            if observed == expected:
                return state
            if observed in ("FAILED", "DELIVERY_PENDING"):
                raise RuntimeError(f"Gateway delivery of {command_id}: {state}")
            time.sleep(0.5)
        raise RuntimeError(f"Timeout awaiting delivery={expected} for {command_id}; last={observed}")


def iso_utc(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def timing(offset_min: int = 120, duration_min: int = 15):
    now = datetime.now(timezone.utc)
    start = now + timedelta(minutes=offset_min)
    end = start + timedelta(minutes=duration_min)
    return iso_utc(start), iso_utc(end), iso_utc(end + timedelta(minutes=1))


def initial_certificate(battery_id: str, version: int, power: float):
    return {
        "batteryId": battery_id,
        "version": version,
        "maxDischargePowerKw": power,
        "minEnergyKwh": 2,
        "maxEnergyKwh": 20,
        "validFrom": "2025-01-01T00:00:00Z",
        "validUntil": "2035-01-01T00:00:00Z",
        "evidenceRef": f"synthetic-exp-certificate-v{version}",
    }


def create_bess(api: Api, bess_id: str):
    result = api.call("POST", "/api/bess", {"id": bess_id, "name": "BESSGuard controlled experiment"}, role="admin")
    assert result.get("id") == bess_id, result


def create_battery(api: Api, bess_id: str, battery_id: str):
    result = api.call("POST", "/api/batteries", {
        "id": battery_id, "bessId": bess_id, "serialNumber": battery_id, "nominalCapacityKwh": 20
    }, role="admin")
    assert result.get("id") == battery_id, result
    cert = api.call("POST", "/api/certificates/initial", initial_certificate(battery_id, 1, 10), role="certifier")
    assert cert.get("version") == 1, cert
    state = api.call("POST", f"/api/gateway/batteries/{battery_id}/activate", {
        "version": 1, "availableEnergyKwh": 15
    }, role="gateway")
    assert state.get("mode") == "ACTIVE", state


def dispatch_payload(id_: str, battery_id: str, power: float, version: int, window):
    start, end, expiry = window
    return {
        "id": id_, "batteryId": battery_id, "certificateVersion": version,
        "startTime": start, "endTime": end, "expiresAt": expiry,
        "requestedPowerKw": power,
    }


def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
