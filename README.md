# BESS-Guard

**BESS-Guard** is a proof-of-concept framework for version-consistent dispatch coordination of second-life battery energy storage systems (BESSs). It uses Hyperledger Fabric for versioned operating certificates and dispatch reservations, RabbitMQ for asynchronous command delivery, and a simulated battery management system (BMS).

This repository accompanies the manuscript **“Version-Consistent Dispatch of Second-Life Batteries Using Blockchain”**, prepared for submission to **CPES 2027 (IFAC conference)**.

## Repository structure

- `bessguard-chaincode/` — Hyperledger Fabric smart contracts and ledger-side validation.
- `bessguard-backend/` — Spring Boot backend, EMS, dispatch gateway, RabbitMQ integration, and BMS simulation.
- `bessguard-experiments/` — Smoke tests, Apache JMeter scenarios, and experimental scripts.

See the component directories for setup and execution instructions.

## Citation

The following reference is provisional; publication details and DOI will be added after publication.

> Miron, R., Hulea, M., and Mureșan, V. (2027). *Version-Consistent Dispatch of Second-Life Batteries Using Blockchain*. Manuscript prepared for CPES 2027 (IFAC).

## License

Apache License 2.0. See [LICENSE](LICENSE).
