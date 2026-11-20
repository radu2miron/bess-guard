# BESSGuard Chaincode

Java 21 / Hyperledger Fabric chaincode for **version-consistent coordination of second-life BESS dispatch**.

- `contract/BessGuardContract.java`: Fabric-facing **façade only** (`@Contract`, `@Transaction`).
- `contract/*Service.java`: one service per responsibility, including admission, certification, gateway coordination, execution reporting.
- `contract/AbstractLedgerService.java`: typed CRUD on namespaced Fabric world-state keys.
- `model/`: UML-aligned domain classes and enums (`@Data`).
- `util/JsonMapper.java`: Jackson serializer matching the supplied pattern.
- `pom.xml`, `metadata.json`, `code-assembly.xml`, `chaincode-package.xml`: same Maven/Fabric package flow as the original.

## Domain data model

| Document | Key | Meaning |
|---|---|---|
| `SecondLifeBESS` | `SECOND_LIFE_BESS:<id>` | Aggregate of reusable battery units |
| `BatteryUnit` | `BATTERY_UNIT:<id>` | Independent, reassignable physical battery identity |
| `BatteryCoordinationState` | `BATTERY_COORDINATION_STATE:<batteryId>` | Current version, mode, energy estimate and **MVCC guard** |
| `OperatingCertificate` | `OPERATING_CERTIFICATE:<batteryId>@<version>` | Immutable versioned limits and issuer identity |
| `DispatchCommand` | `DISPATCH_COMMAND:<id>` | Version-bound reservation and processing status |
| `ExecutionAttempt` | `EXECUTION_ATTEMPT:<commandId>@<attemptId>` | Separate accepted/rejected attempt and outcome evidence |

The aggregation `SecondLifeBESS → BatteryUnit` is modeled by `BatteryUnit.bessId`, not composition. Certificates and commands retain stable battery references across BESS reassignment. `BatteryCoordinationState` has the same logical ID as the battery, and execution attempts are uniquely attached to a command.

## Authorization

Application roles (`ADMIN`, `CERTIFIER`, `EMS`, `GATEWAY`) are enforced by the **Spring Boot backend** (Spring Security), not by Fabric certificate attributes:

- `ADMIN`: register BESS and batteries, administer resources.
- `CERTIFIER`: issue initial certificates, begin/retry recertification and activate versions.
- `EMS`: submit/cancel dispatch reservations.
- `GATEWAY`: acknowledge quiescence, install versions, report energy, authorize execution and report outcomes.

The chaincode requires a **single trusted Fabric service identity** for state-changing operations. The development allowlist in `DomainSupport.java` is `Org1MSP` with certificate subject CN `User1@org1.example.com`. The Spring Boot Fabric Gateway client must sign transactions with that identity. No `bessguard.role` certificate attributes or Fabric CA changes are required for these application roles. If invoking the chaincode directly during testing, use the same service identity (not `Admin@org1.example.com`). The roles shown in the API table below are **backend roles**, not roles checked by chaincode.

**Security note:** a trusted service identity can invoke any chaincode mutation if it has direct Fabric access, bypassing Spring Security. Protect the signing key, restrict direct access, and use stronger identity pinning and appropriate endorsement policies in production. Fabric issuer/audit fields identify the **backend service**, not the individual Spring Boot user or external laboratory. The chaincode does not verify external laboratory signatures in `evidenceRef`.

## Facade API

Each method below is a public transaction on `BessGuardContract`. JSON arguments are passed as strings (matching the original DPP façade).

| Function | Backend role | Purpose |
|---|---|---|
| `registerBess(json)` | ADMIN | Register BESS |
| `registerBattery(json)` | ADMIN | Register battery and initialize coordination guard |
| `assignBatteryToBess(batteryId,bessId)` | ADMIN | Reassign only if SUSPENDED and no active reservations |
| `issueInitialCertificate(json)` | CERTIFIER | Issue v1; set UPDATED (not ACTIVE) |
| `activateGatewayVersion(batteryId,version,energyKwh)` | GATEWAY | Ack locally installed version; enable dispatch |
| `reserveDispatch(json)` | EMS | Reserve within power/energy/validity constraints |
| `cancelDispatch(commandId)` | EMS/GATEWAY | Cancel not-yet-dispatched reservation |
| `beginRecertification(json)` | CERTIFIER | Stage v+1 and enter QUIESCING |
| `acknowledgeQuiescence(batteryId)` | GATEWAY | Fence old commands, cancel pending; require no DISPATCHED attempts |
| `activateCertificate(batteryId)` | CERTIFIER | Set ledger active version v+1 and UPDATED |
| `retryQuiescence(batteryId)` | CERTIFIER | Retry a staged recertification after suspension |
| `reportAvailableEnergy(batteryId,energyKwh)` | GATEWAY | Update gateway-provided energy snapshot |
| `suspendDispatch(batteryId)` | GATEWAY/CERTIFIER | Suspend dispatch (not a physical emergency stop) |
| `authorizeExecution(commandId,attemptId)` | GATEWAY | Persist the authorization attempt; fence at ledger version |
| `reportExecutionOutcome(commandId,attemptId,outcome,deliveredEnergyKwh)` | GATEWAY | Record COMPLETED/FAILED outcome, reconcile energy estimate |

Read-only facade methods: `readBess`, `getAllBess`, `readBattery`, `getAllBatteries`, `readBatteryCoordinationState`, `readOperatingCertificate`, `getAllOperatingCertificates`, `readDispatch`, `getAllDispatchCommands`, `readExecutionAttempt`, `getAllExecutionAttempts`. `getAll...` endpoints are development utilities and **not paginated**.

## JSON examples

BESS:

```json
{"docType":"SECOND_LIFE_BESS","id":"bess-01","name":"Second-life storage cluster"}
```

Battery unit:

```json
{"docType":"BATTERY_UNIT","id":"bat-01","bessId":"bess-01","serialNumber":"SN-001","nominalCapacityKwh":20}
```

First operating certificate:

```json
{"docType":"OPERATING_CERTIFICATE","batteryId":"bat-01","version":1,"maxDischargePowerKw":10,"minEnergyKwh":2,"maxEnergyKwh":20,"validFrom":"2026-12-01T00:00:00Z","validUntil":"2027-12-01T00:00:00Z","evidenceRef":"inspection-001"}
```

Dispatch command (choose `startTime`, `endTime`, and `expiresAt` in the future relative to the proposal timestamp):

```json
{"docType":"DISPATCH_COMMAND","id":"dispatch-001","batteryId":"bat-01","certificateVersion":1,"startTime":"2027-01-01T10:00:00Z","endTime":"2027-01-01T10:15:00Z","expiresAt":"2027-01-01T10:16:00Z","requestedPowerKw":9}
```

Recertification v2: same certificate format with `version: 2`, `maxDischargePowerKw: 6` and a new `evidenceRef`.

All times are UTC ISO-8601 (`Z`) strings. Powers are kW; energies are kWh. Prototype reservations assume **piecewise-constant positive discharge power**, not bidirectional battery scheduling.

## Protocol sequence

1. ADMIN registers BESS and battery; certificate state starts SUSPENDED with no active version.
2. CERTIFIER issues v1; ledger goes to UPDATED (dispatch disabled).
3. GATEWAY **first** installs v1 locally, locks its fence to v1 and reports current energy via `activateGatewayVersion`; ledger goes ACTIVE.
4. EMS proposes `reserveDispatch`. Only a **VALID committed transaction** is a real reservation; the proposal response alone is insufficient.
5. GATEWAY submits `authorizeExecution` when dispatch time begins. It waits for a **VALID** commit, then validates its **locally installed version and BMS safety conditions** before any physical actuation. Persist a unique `attemptId` and never treat a replayed attempt as new authority.
6. GATEWAY calls `reportExecutionOutcome` after local operation, to release the reservation and record the observed energy delivery.
7. CERTIFIER stages v2 with `beginRecertification`; this writes the same per-battery guard key as admissions, forcing concurrent conflicting proposals to fail Fabric MVCC validation.
8. GATEWAY prevents new/old commands from running, ensures all DISPATCHED attempts are settled, then calls `acknowledgeQuiescence`; outstanding RESERVED commands become CANCELLED.
9. CERTIFIER calls `activateCertificate` (UPDATED, no dispatch); GATEWAY installs v2 and invokes `activateGatewayVersion` to resume ACTIVE.
10. Failure can suspend dispatch. A staged suspended recertification can be retried using `retryQuiescence`.

## Consistency and known scope limits

- The `BatteryCoordinationState` key is **read and written** on admissions, certificate transitions, and execution decisions, so conflicting transactions on the **same battery** must be retried after an MVCC invalidation. Independent batteries do not share this key.
- Feasibility is checked without unsafe rich-query phantoms: open reservation IDs are held in the guard and explicit document keys are read. Overlapping half-open intervals `[start,end)` are checked at each boundary. Unfinished reservations also consume a conservative future energy budget. An early attempted execution does not destroy its valid future reservation.
- Chaincode endorsement/simulation success **is not final commit success**. Client applications must observe validation code / commit status before dispatching to the broker or actuating.
- The chaincode has **no direct connection to the broker, BMS, or battery**. Physical execution is possible only via a trustworthy gateway that persistently fences local commands, including across restarts. Fabric cannot guarantee immediate revocation of disconnected equipment.
- Fabric transaction timestamps come from client proposals; they are not by themselves trusted wall clocks. The gateway must independently enforce local deadlines and certificate validity before actuation.
- Energy availability is a gateway-supplied **snapshot**; if a new report cannot support outstanding reservations, coordination is suspended. The local BMS remains responsible for physical constraints. This prototype does not perform electrochemical SoH estimation or optimize multi-slot battery charge/discharge.
- For simplicity, the open reservation list is stored in the guard key. This is a deliberate **per-battery serialization trade-off** and has bounded-scale prototype limitations. Production may require time-bucket guards, pruning, pagination and ledger indexing.
- This project does not include the backend/gateway, message broker, BESS simulator or peer network from the manuscript. Those must be implemented and tested separately.

## Tests

`src/test/java/edu/tucn/bessguard/contract/BessGuardContractTest.java` includes unit scenarios for initial gateway activation, overlapping-power conflicts, conservative energy budgets, stale-version recertification, in-flight operations, early attempts, and trusted backend identity enforcement. These tests mock the peer world state; they do **not** prove Fabric peer-level concurrency or physical actuation safety. Repeat MVCC and endorsement tests on a real Fabric network.

## Steps to build, deploy and run the chaincode

### 1. Build chaincode
1.1. Build bessguard-chaincode:
```mvn clean install```

Expected artifacts, as in the reference project:

- `target/bessguard-chaincode-1.0.0.jar` (shaded chaincode Java archive)
- `target/code.tar.gz`
- `target/bessguard-chaincode-1.0.0.tar.gz` (Fabric lifecycle install package)

1.2. Copy ```target/bessguard-chaincode-1.0.0.tar.gz``` to ```../fabric/test-network/bessguard-chaincode-1.0.0.tar.gz```

### 2. Open terminal and run the following commands to set up the environment variables:
```
export CONTAINER_CLI_COMPOSE="docker compose"
cd ../fabric/test-network
export PATH=${PWD}/../bin:$PATH
export FABRIC_CFG_PATH=$PWD/../config/
peer version
```

### 3. Bring network up and create channel
```
./network.sh up -s couchdb
./network.sh createChannel -c bessguard-channel
```

### 4. Install chaincode on peers
4.1. Install chaincode on Org1 peer
```
export CORE_PEER_TLS_ENABLED=true
export CORE_PEER_LOCALMSPID="Org1MSP"
export CORE_PEER_TLS_ROOTCERT_FILE=${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt
export CORE_PEER_MSPCONFIGPATH=${PWD}/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp
export CORE_PEER_ADDRESS=localhost:7051

peer lifecycle chaincode install bessguard-chaincode-1.0.0.tar.gz
```

4.2. Install chaincode on Org2 peer
```
export CORE_PEER_LOCALMSPID="Org2MSP"
export CORE_PEER_TLS_ROOTCERT_FILE=${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt
export CORE_PEER_MSPCONFIGPATH=${PWD}/organizations/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp
export CORE_PEER_ADDRESS=localhost:9051

peer lifecycle chaincode install bessguard-chaincode-1.0.0.tar.gz
```

### 5. Approve chaincode definition
5.1. Setup chaincode environment variables
```
peer lifecycle chaincode queryinstalled
export CC_PACKAGE_ID=<REPLACE WITH PACKAGE ID, E.G. bessguard-chaincode-1.0.0:69de748301770f6ef64b42aa6bb6cb291df20aa39542c3ef94008615704007f3>
```

5.2. Approve chaincode for Org2 - env. variables already setup for this org at previous step
```
peer lifecycle chaincode approveformyorg -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --channelID bessguard-channel --name bessguard-chaincode --version 1.0 --package-id $CC_PACKAGE_ID --sequence 1 --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem"
```

5.3. Approve chaincode for Org1
```
export CORE_PEER_LOCALMSPID="Org1MSP"
export CORE_PEER_MSPCONFIGPATH=${PWD}/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp
export CORE_PEER_TLS_ROOTCERT_FILE=${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt
export CORE_PEER_ADDRESS=localhost:7051

peer lifecycle chaincode approveformyorg -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --channelID bessguard-channel --name bessguard-chaincode --version 1.0 --package-id $CC_PACKAGE_ID --sequence 1 --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem"
```

### 6. Commit chaincode
```
peer lifecycle chaincode checkcommitreadiness --channelID bessguard-channel --name bessguard-chaincode --version 1.0 --sequence 1 --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem" --output json
peer lifecycle chaincode commit -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --channelID bessguard-channel --name bessguard-chaincode --version 1.0 --sequence 1 --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem" --peerAddresses localhost:7051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt" --peerAddresses localhost:9051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt"
peer lifecycle chaincode querycommitted --channelID bessguard-channel --name bessguard-chaincode
```

### 7. Stop and clean up
To stop the network and clean up the volumes (i.e. erase data), run:
```
./network.sh down
```
