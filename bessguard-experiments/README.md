# BESSGuard — minimal CPES 2027 experiments

Two controlled experiments for **version-consistent dispatch** using the **actual `bessguard-backend` REST endpoints** (not the obsolete `/sync` and `/async` paths). The experiments assume the existing Fabric chaincode, Spring Boot backend and RabbitMQ are running; they **do not** emulate the blockchain or invent results.

| Experiment | Tool | Fresh fixtures | Pass criterion |
|---|---|---|---|
| **E1 — competing reservations** | **Apache JMeter 5.6.x** + Python ledger verifier | 20 new batteries, one per request pair | For each battery (10-kW limit), of two simultaneous overlapping requests (8 kW, 6 kW), **exactly one** is committed; the other is not. At most one open reservation in ledger. |
| **E2 — version change and obsolete command** | Python 3 stdlib HTTP orchestration | 10 new batteries | Each command v1 (9 kW) is staged, then cancelled on recertification to v2 (6 kW), rejected by gateway without simulated energy loss; a new v2 reservation (5 kW) is admitted and staged. Optional: explicitly re-inject the old message into RabbitMQ **after** v2 activation. |

**Do not treat deliberate HTTP error responses as test failures in E1.** One `502/409/4xx` response per pair is expected, depending on Fabric failure propagation. What matters is *commit validity and ledger state*. E1 also checks the start-time skew from JMeter's JTL results (default <=2 s). The current backend uses a 502 response for Fabric exceptions.

## Prerequisites

- Linux with `python3`, and Apache **JMeter 5.6.x** executable as `jmeter` in `PATH` (for E1 only).
- Running Hyperledger Fabric `bessguard-channel`, `bessguard-chaincode`; Spring Boot `bessguard-backend` on `http://localhost:8081`; RabbitMQ (AMQP 5672) with the configured queue and working listener.
- `BESSGUARD_JWT_SECRET_BASE64` correctly configured in the **backend** (the experiments use REST JWT logins, never Fabric private keys).
- Accounts for `ADMIN` (`user1` by default), `CERTIFIER`, `EMS`, `GATEWAY`. The passwords below are read by the test scripts from environment variables.
- For actual **late RabbitMQ redelivery** in E2, *optional*: RabbitMQ Management plugin/API on port 15672 with permissions to publish to the `bessguard-dispatch-queue` queue (normally available with RabbitMQ `-management` images). Basic E2 does **not** need the Management plugin.

For example, in the terminal **running the tests**, set:

```bash
export BESSGUARD_BACKEND_URL=http://localhost:8081
export BESSGUARD_ADMIN_USERNAME=user1
export BESSGUARD_ADMIN_PASSWORD='YOUR_ADMIN_PASSWORD'
export BESSGUARD_CERTIFIER_PASSWORD='YOUR_CERTIFIER_PASSWORD'
export BESSGUARD_EMS_PASSWORD='YOUR_EMS_PASSWORD'
export BESSGUARD_GATEWAY_PASSWORD='YOUR_GATEWAY_PASSWORD'
```

The backend itself must also have these accounts configured when started. **Do not commit secrets, JWTs, cryptographic materials, JTL files containing auth data, or production credentials to Git.** The JMeter token is temporarily visible in local process arguments (`-JjwtToken`). Run only on your own controlled test machine; use a test-only identity and clean logs if needed.

### E1: exactly two simultaneous HTTP reservations per battery

Run from this directory:

```bash
E1_PAIRS=20 bash run-e1.sh
```

The runner will:

1. Log in over REST using JWT, create one experiment BESS and **20 distinct battery units**, each with an active certificate v1 (`maxDischargePowerKw=10`, `availableEnergyKwh=15`). These setup operations are **not included in JMeter timing**.
2. Write a two-row-per-pair CSV with commands requiring **8 kW and 6 kW**, overlapping for exactly 15 minutes in a future two-hour window. The 14-kW combined demand violates the 10-kW limit while either command alone is feasible.
3. Run `jmeter/e1_concurrent_reservations.jmx` in **CLI mode** with **2 threads**, **20 loops**, shared CSV input and a **Synchronizing Timer of 2**. Each pair is synchronized before submission; `timeStamp` start skew is checked afterward.
4. Query Fabric **through the backend** (`GET /api/dispatch`, `GET /api/batteries/{id}/state`) and cross-check the committed state against both JMeter responses.
5. Create a `results/e1-<timestamp>-<pid>/` directory with `fixture-e1.json`, `requests-e1.csv`, `results-e1.jtl`, and `summary-e1.json`.

To test fewer pairs first, use `E1_PAIRS=2 bash run-e1.sh`. **Each run creates fresh identities**; do not rerun JMeter with an old CSV, as the chaincode correctly rejects duplicate command IDs.

The verifier reports: number of valid simultaneous pairs, HTTP status counts, invariant violations, start-time skew, mean and p95 latency **of successful committed reservations only**. A `false` summary/pass means do not claim success in the paper. HTTP “error %” in JMeter is misleading here, since one rejection per pair is intentional.

To reverify an existing E1 run without sending new reservations:

```bash
python3 scripts/verify_e1.py \
  --fixture results/e1-YOUR-RUN/fixture-e1.json \
  --jtl results/e1-YOUR-RUN/results-e1.jtl \
  --output results/e1-YOUR-RUN/summary-e1.json
```

### E2: recertification with already-staged stale command

```bash
E2_RUNS=10 bash run-e2.sh
```

For each new battery, the script:

1. Activates certificate **v1 (10 kW)** and a software BMS at **15 kWh**.
2. Reserves command v1 at **9 kW**, future execution time, waits until RabbitMQ confirms the gateway has **STAGED** it.
3. Begins recertification v2 (6 kW), requests quiescence acknowledgment (which **CANCELS** the v1 reservation), activates certificate v2 and reactivates the gateway.
4. Attempts to execute the **previously staged v1 command**, expecting a **REJECTED** gateway decision, a `CANCELLED` ledger command and *no reduction* in BMS `availableEnergyKwh`.
5. Confirms that a new **5-kW v2 command** is accepted and stages successfully.

This base test checks an **obsolete staged command**; it does **not** literally delay the original RabbitMQ message. To also inject a real *late* broker delivery after recertification, enable the Management API test:

```bash
export BESSGUARD_RABBIT_MANAGEMENT_URL=http://localhost:15672
export BESSGUARD_RABBIT_USER=guest
export BESSGUARD_RABBIT_PASSWORD='YOUR_RABBIT_PASSWORD'
E2_LATE_RABBIT=1 E2_RUNS=10 bash run-e2.sh
```

The optional path publishes the old command reference to `bessguard-dispatch-queue` **after** v2 is ACTIVE. It expects RabbitMQ to route the message and the dispatch listener to mark it `IGNORED`. This tests late/duplicate broker delivery, in addition to gateway rejection and unchanged simulated energy. The test requires a queue consumer to be running. On a RabbitMQ deployment without Management plugin, leave `E2_LATE_RABBIT=0` and **do not describe the base case as a deliberately delayed broker message**.

E2 outputs `results/e2-.../summary-e2.json` and `results-e2.csv` for the paper. Results represent **software-simulated BMS accounting**, not measured battery energy.

## Offline validation of the test package

```bash
python3 scripts/selfcheck.py
bash -n run-e1.sh run-e2.sh
```

The offline check does not contact the live system and **is not an experimental result**. It only verifies file structure, JMX settings, fixtures and Python syntax.

## How to use these results in the six-page IFAC paper

A compact table may be sufficient:

| Scenario | Repetitions | Criterion | Observed (fill in after execution) |
|---|---:|---|---|
| Concurrent 8/6-kW reservations, 10-kW limit (E1) | 20 pairs | Exactly one committed reservation per pair; no conflicting commitments | **TBD** |
| Recertification 10 -> 6 kW with old v1 command (E2) | 10 runs | v1 cancelled/rejected, unchanged simulated energy, new v2 command staged | **TBD** |

Describe E2 as **late RabbitMQ delivery** only if the `E2_LATE_RABBIT=1` path is completed successfully. Report the observed JMeter success latency with context, if space permits, but **do not** claim blockchain scalability, performance superiority, hardware safety, real battery response, or statistically representative throughput from these small functional experiments.

### Implementation/measurement constraints

- Single backend and Fabric test-network; shared host resource contention is possible.
- E1 creates a separate battery for each pair, isolating state and leaving a clean baseline for each conflict. Within each pair only, commands deliberately contend for the *same* battery state key.
- Commands are scheduled far into the future, so the tests focus on **coordination and admission**, not energy services in real time.
- Fabric `submitTransaction` confirms **VALID commit** before the backend publishes a RabbitMQ command. Invalid/failed commits should not produce an accepted ledger reservation.
- The software gateway and software BMS live in the Spring Boot process, including **nonpersistent local state**; tests require that backend and broker remain running and are not restarted mid-test.
- Each experiment uses unique resource IDs and leaves test records in the ledger. A separate cleanup or resetting the disposable Fabric test-network is the user's decision.
