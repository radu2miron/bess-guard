# BESSGuard — Spring Boot backend

Prototype backend for *Version-Consistent Dispatch of Second-Life Battery Storage Using Blockchain* (CPES 2027). This project implements the **components of the conceptual architecture**, not a pair of duplicate synchronous/asynchronous application APIs.

## Architectural flow

```text
Authorized certifier ────────────> blockchain (certificates and recertification)
Energy manager (EMS) ──reserve───> blockchain (admission + VALID commit)
Energy manager (EMS) ─after commit─> RabbitMQ (committed command ID)
RabbitMQ ─────────────────────────> dispatch gateway (version fencing)
Dispatch gateway ─authorization──> blockchain (VALID execution decision)
Dispatch gateway ─validated cmd──> software BMS / second-life BESS simulator
Dispatch gateway ─outcome────────> blockchain (execution record)
```

The blockchain is **not in a physical real-time control loop**. The gateway and BMS are software simulators, with no external battery hardware. This is a proof-of-concept, not a safety-certified controller.

### Package structure

- `controller/` — domain-oriented REST controllers for BESS, batteries, certificates, EMS, dispatch and gateway; **no `/sync` or `/async` duplicate routes**.
- `service/BlockchainService` — the Fabric client adapter used by internal services and by an authorized REST facade.
- `service/AuthorizedBlockchainService` — API role checks delegated to Spring Security's authenticated identity.
- `service/EnergyManagementService` — simple greedy peak-shaving allocation, committed dispatch reservations, and RabbitMQ publication **after successful commit**.
- `queue/DispatchMessagePublisher` and `DispatchMessageListener` — asynchronous delivery of committed commands to the gateway, *not* asynchronous submission of arbitrary Fabric transactions.
- `service/SimulatedGatewayService` — local version fence, quiescence acknowledgment, command staging, and execution authorization.
- `service/SimulatedBmsService` — software-only power and energy limits, and logical discharge accounting.
- `security/login` and JWT Bearer (HS512) approach, with roles assigned by backend configuration.

## Prerequisites

- Java 21 and Maven
- Running `bessguard-chaincode` on the `bessguard-channel` Fabric network
- RabbitMQ on port 5672
- A Fabric service identity (`Org1MSP`, `User1@org1.example.com` by default) accepted by the chaincode

Configure `blockchain.crypto-path` in `src/main/resources/application.properties` or set `BESSGUARD_FABRIC_CRYPTO_PATH` to the Org1 peer organization directory. The peer endpoint defaults to `localhost:7051`.

The RabbitMQ configuration: a **durable queue**, `Jackson2JsonMessageConverter`, `RabbitTemplate`, `RabbitAdmin`, `prefetch=1`, and `50` consumers (configurable via `rabbit.consumer.concurrency`). The queue is now `bessguard-dispatch-queue`, since its contents are dispatch commands.

## Authentication

The `POST /login` accepts JSON or form data:

```bash
curl -sS -X POST http://localhost:8081/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user1","password":"pwd1"}'
```

Pass `Authorization: Bearer <token>` to all other endpoints. Use role-bound logins (`ADMIN`, `CERTIFIER`, `EMS`, `GATEWAY`). Dev defaults: `user1/pwd1` is ADMIN; set `BESSGUARD_CERTIFIER_PASSWORD`, `BESSGUARD_EMS_PASSWORD`, and `BESSGUARD_GATEWAY_PASSWORD` for the remaining users. Change the default password and configure `BESSGUARD_JWT_SECRET_BASE64` for any externally reachable deployment. Spring Security handles API-level roles; a **single Fabric backend identity** signs transactions. This design does not provide independent per-organization blockchain authorization.

## API operations

| REST endpoint | Fabric operation or behavior | Role |
|---|---|---|
| `POST /api/bess` | `registerBess` | ADMIN |
| `GET /api/bess`, `GET /api/bess/{id}` | read BESS | authenticated |
| `POST /api/batteries` | `registerBattery` | ADMIN |
| `PUT /api/batteries/{batteryId}/assignment` | `assignBatteryToBess` | ADMIN |
| `GET /api/batteries`, `GET /api/batteries/{id}`, `GET /api/batteries/{id}/state` | read batteries and state | authenticated |
| `POST /api/certificates/initial` | `issueInitialCertificate` | CERTIFIER |
| `POST /api/certificates/recertification` | `beginRecertification`; close local fence after commit | CERTIFIER |
| `POST /api/certificates/{batteryId}/retry-quiescence` | `retryQuiescence` | CERTIFIER |
| `POST /api/certificates/{batteryId}/activate` | `activateCertificate` | CERTIFIER |
| `GET /api/certificates`, `GET /api/certificates/{batteryId}/{version}` | read certificates | authenticated |
| `POST /api/ems/peak-shaving` | greedy multi-battery reservation planning | EMS |
| `POST /api/dispatch` | `reserveDispatch` **commit first**, then publish to RabbitMQ | EMS |
| `POST /api/dispatch/{commandId}/redeliver` | re-publish a committed RESERVED command | EMS |
| `POST /api/dispatch/{commandId}/cancel` | `cancelDispatch` | EMS |
| `GET /api/dispatch`, `GET /api/dispatch/{commandId}` | read dispatches | authenticated |
| `GET /api/dispatch/{commandId}/delivery` | in-memory broker/gateway delivery diagnostic | authenticated |
| `POST /api/gateway/batteries/{batteryId}/quiescence/ack` | close local fence, `acknowledgeQuiescence` | GATEWAY |
| `POST /api/gateway/batteries/{batteryId}/activate` | install limits, `activateGatewayVersion` | GATEWAY |
| `POST /api/gateway/batteries/{batteryId}/energy` | `reportAvailableEnergy` | GATEWAY |
| `POST /api/gateway/batteries/{batteryId}/suspend` | `suspendDispatch` | GATEWAY |
| `POST /api/gateway/batteries/{batteryId}/reconcile` | recover local fence from ledger if safe | GATEWAY |
| `GET /api/gateway/batteries/{batteryId}` | read local simulated gate/BMS state | authenticated |
| `POST /api/gateway/dispatch/{commandId}/execute` | simulate execution **only if delivered by RabbitMQ** | GATEWAY |
| `GET /api/gateway/dispatch/{commandId}/attempts/{attemptId}`, `GET /api/gateway/attempts` | read execution attempts | authenticated |

`ADMIN` is a permitted override in the example backend role checker. Gateway actions do not expose raw public `authorizeExecution` or `reportExecutionOutcome` mutations: those are internal operations of the version-fenced gateway.

## Command delivery and dispatch

`POST /api/dispatch` returns a `reservation` object and a `deliveryState`. `reservation.status=RESERVED` means **the Fabric transaction committed VALID**. `deliveryState=QUEUED` indicates a RabbitMQ publication attempt succeeded, not that the command has already been executed. If the broker fails after reservation commitment, the API returns `DELIVERY_PENDING` instead of falsely reporting the reservation as failed; retry with `POST /api/dispatch/{id}/redeliver`.

After receiving a message, the gateway reads the authoritative Fabric state, stages eligible commands, and the internal scheduler checks staged commands every second (`simulator.dispatch.poll-ms`). It calls the gateway's execution method when the command's scheduled start time is reached. Only after local validation **and** valid Fabric authorization does the software BMS account for the discharge, followed by a ledger outcome report.

Dispatch delivery is **at-least-once in intent**, with idempotent gateway staging. The prototype does not implement a transactional outbox or durable recovery of gateway-local state; after a backend restart, call `/api/gateway/batteries/{id}/reconcile` and redeliver outstanding RESERVED commands from the ledger. For production, implement a durable outbox and broker publisher confirmations.

## Recertification workflow

1. Certifier calls `/api/certificates/recertification`: blockchain transitions `ACTIVE -> QUIESCING`; the gateway closes its local fence.
2. Gateway calls `/api/gateway/batteries/{id}/quiescence/ack` after blocking old-version operations.
3. Certifier calls `/api/certificates/{id}/activate`: new version enters `UPDATED`.
4. Gateway calls `/api/gateway/batteries/{id}/activate` with `{"version":2,"availableEnergyKwh":15}`: local BMS installs new limits, then Fabric confirms `ACTIVE`.
5. Previously delivered commands carrying an old certificate version fail the gateway fence or ledger authorization. A duplicate broker message cannot recreate a reservation.

A single-process simulated BMS cannot provide real hardware safety assurances; external laboratory signatures referenced by `evidenceRef` are not independently verified by this prototype.

## Run and test

```bash
mvn clean test package
mvn spring-boot:run
```

Swagger: `http://localhost:8081/swagger-ui/index.html`.

```bash
export BESSGUARD_CERTIFIER_PASSWORD=your-certifier-password
export BESSGUARD_EMS_PASSWORD=your-ems-password
export BESSGUARD_GATEWAY_PASSWORD=your-gateway-password
bash ../tests/bessguard-backend-http-smoke-test.sh
```

The HTTP smoke test covers a basic 10 kW → 6 kW recertification with a previously reserved command. It needs a running Fabric network, RabbitMQ, and separate configured JWT credentials.

**Experimental limits:** only the individual reservations and execution attempts are committed atomically; the greedy multi-battery EMS plan is best-effort, not an atomic global optimizer. Broker statuses, gateway staging and software BMS energy estimates are in memory. No real physical battery, battery degradation model, external certificate-signature verification or durable outbox is included.
