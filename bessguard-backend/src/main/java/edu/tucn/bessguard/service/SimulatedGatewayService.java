package edu.tucn.bessguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.DispatchMessage;
import edu.tucn.bessguard.model.dto.GatewayActivationRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Dispatch gateway: receives RabbitMQ commands, fences execution against certificate versions,
 * obtains a VALID Fabric authorization, and forwards the permitted action to the BMS simulator.
 * The local fence starts CLOSED after each restart; the operator must reconcile state.
 */
@Service
@Log4j2
public class SimulatedGatewayService {

    private final BlockchainService chain;
    private final SimulatedBmsService bms;
    private final DeliveryStatusService deliveryStatuses;
    private final ConcurrentMap<String, LocalGateway> localBatteries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> pendingCommands = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicBoolean> inProgress = new ConcurrentHashMap<>();

    public SimulatedGatewayService(BlockchainService chain, SimulatedBmsService bms,
                                   DeliveryStatusService deliveryStatuses) {
        this.chain = chain;
        this.bms = bms;
        this.deliveryStatuses = deliveryStatuses;
    }

    /** A duplicate broker message never triggers a second execution. */
    public boolean stage(DispatchMessage message) {
        JsonNode command = chain.read(BessGuardOperation.READ_DISPATCH, message.commandId());

        if (!message.batteryId().equals(command.path("batteryId").asText())) {
            throw new IllegalArgumentException("Broker command refers to a different battery.");
        }

        if (!"RESERVED".equals(command.path("status").asText())) {
            return false;
        }

        JsonNode state = chain.read(BessGuardOperation.READ_COORDINATION_STATE, message.batteryId());

        if (!"ACTIVE".equals(state.path("mode").asText())
                || state.path("activeCertificateVersion").asInt() != command.path("certificateVersion").asInt()
                || !Instant.now().isBefore(Instant.parse(command.path("expiresAt").asText()))
                || !Instant.now().isBefore(Instant.parse(command.path("endTime").asText()))) {
            return false;
        }

        pendingCommands.putIfAbsent(message.commandId(), message.batteryId());
        return true;
    }

    /** Temporarily closes the local fence after a committed QUIESCING transition. */
    public void freeze(String batteryId) {
        LocalGateway local = localBatteries.computeIfAbsent(batteryId, ignored -> new LocalGateway());

        synchronized (local) {
            local.active = false;
        }
    }

    public JsonNode acknowledgeQuiescence(String batteryId) {
        LocalGateway local = localBatteries.computeIfAbsent(batteryId, ignored -> new LocalGateway());

        synchronized (local) {
            local.active = false;
            return chain.submit(BessGuardOperation.ACKNOWLEDGE_QUIESCENCE, batteryId);
        }
    }

    public JsonNode activate(String batteryId, GatewayActivationRequest request) {
        LocalGateway local = localBatteries.computeIfAbsent(batteryId, ignored -> new LocalGateway());

        synchronized (local) {
            local.active = false;
            JsonNode cert = chain.read(BessGuardOperation.READ_CERTIFICATE, batteryId,
                    Integer.toString(request.version()));
            bms.install(batteryId, cert.path("maxDischargePowerKw").asDouble(),
                    cert.path("minEnergyKwh").asDouble(), request.availableEnergyKwh());
            local.version = request.version();

            // A successful VALID commit is mandatory before reopening the gateway fence.
            JsonNode committed = chain.submit(BessGuardOperation.ACTIVATE_GATEWAY_VERSION, batteryId,
                    Integer.toString(request.version()), Double.toString(request.availableEnergyKwh()));
            local.active = true;
            return committed;
        }
    }

    public JsonNode updateEnergy(String batteryId, double availableEnergyKwh) {
        LocalGateway local = localBatteries.computeIfAbsent(batteryId, ignored -> new LocalGateway());

        synchronized (local) {
            local.active = false;
            JsonNode committed = chain.submit(BessGuardOperation.REPORT_AVAILABLE_ENERGY, batteryId,
                    Double.toString(availableEnergyKwh));
            bms.updateEnergy(batteryId, availableEnergyKwh);
            local.active = "ACTIVE".equals(committed.path("mode").asText());
            return committed;
        }
    }

    public JsonNode suspend(String batteryId) {
        LocalGateway local = localBatteries.computeIfAbsent(batteryId, ignored -> new LocalGateway());

        synchronized (local) {
            local.active = false;
            return chain.submit(BessGuardOperation.SUSPEND_DISPATCH, batteryId);
        }
    }

    /**
     * Execute a *staged* command only within its interval. This is a one-step simulation of
     * total interval energy, not continuous discharge or independent proof of physical delivery.
     */
    public Map<String, Object> execute(String commandId) {
        String batteryId = pendingCommands.get(commandId);

        if (batteryId == null) {
            return rejected(commandId, "Command was not delivered by the message broker to this gateway.");
        }

        AtomicBoolean busy = inProgress.computeIfAbsent(commandId, ignored -> new AtomicBoolean());

        if (!busy.compareAndSet(false, true)) {
            return rejected(commandId, "Command is already being processed.");
        }

        try {
            LocalGateway local = localBatteries.computeIfAbsent(batteryId, ignored -> new LocalGateway());

            synchronized (local) {
                JsonNode command = chain.read(BessGuardOperation.READ_DISPATCH, commandId);
                int requestedVersion = command.path("certificateVersion").asInt();
                Instant start = Instant.parse(command.path("startTime").asText());
                Instant end = Instant.parse(command.path("endTime").asText());
                Instant expiration = Instant.parse(command.path("expiresAt").asText());
                Instant now = Instant.now();
                double requestedPower = command.path("requestedPowerKw").asDouble();
                double energyKwh = requestedPower * (Duration.between(start, end).toMillis() / 3_600_000.0);

                if (!"RESERVED".equals(command.path("status").asText())) {
                    pendingCommands.remove(commandId);
                    return rejected(commandId, "Command is no longer RESERVED in the ledger.");
                }

                if (!local.active || local.version != requestedVersion) {
                    pendingCommands.remove(commandId);
                    return rejected(commandId, "Local gateway version fence is closed or stale.");
                }

                if (now.isBefore(start)) {
                    return Map.of("commandId", commandId, "decision", "DEFERRED", "reason", "Start time not reached.");
                }

                if (!now.isBefore(end) || !now.isBefore(expiration)) {
                    pendingCommands.remove(commandId);
                    return rejected(commandId, "Command execution window expired.");
                }

                if (!bms.canDischarge(batteryId, requestedPower, energyKwh)) {
                    pendingCommands.remove(commandId);
                    return rejected(commandId, "Simulated BMS power or energy constraint exceeded.");
                }

                // Fabric commit and its version guard are checked before *any* local discharge.
                String attemptId = UUID.randomUUID().toString();
                JsonNode attempt = chain.submit(BessGuardOperation.AUTHORIZE_EXECUTION, commandId, attemptId);
                pendingCommands.remove(commandId);

                if (!"ACCEPTED".equals(attempt.path("decision").asText())) {
                    return rejected(commandId, attempt.path("reason").asText());
                }

                bms.discharge(batteryId, energyKwh);

                try {
                    JsonNode outcome = chain.submit(BessGuardOperation.REPORT_EXECUTION_OUTCOME,
                            commandId, attemptId, "COMPLETED", Double.toString(energyKwh));
                    Map<String, Object> result = new HashMap<>();
                    result.put("commandId", commandId);
                    result.put("attemptId", attemptId);
                    result.put("decision", "ACCEPTED");
                    result.put("outcome", outcome);
                    result.put("simulatedEnergyKwh", energyKwh);
                    deliveryStatuses.update(commandId, "EXECUTED", "Simulated discharge completed and reported to ledger.");
                    return result;
                } catch (RuntimeException e) {
                    local.active = false;
                    deliveryStatuses.update(commandId, "OUTCOME_UNCONFIRMED",
                            "Local simulation applied, but outcome commit failed. Reconciliation required.");
                    throw e;
                }
            }
        } finally {
            busy.set(false);
        }
    }

    /** Automatic delivery of scheduled commands received through the RabbitMQ queue. */
    @Scheduled(fixedDelayString = "${simulator.dispatch.poll-ms:1000}")
    public void executeDueCommands() {

        for (String commandId : pendingCommands.keySet()) {
            String batteryId = pendingCommands.get(commandId);

            try {

                if (batteryId == null || !isActive(batteryId)) {
                    continue;
                }

                JsonNode command = chain.read(BessGuardOperation.READ_DISPATCH, commandId);
                Instant start = Instant.parse(command.path("startTime").asText());

                if (Instant.now().isBefore(start)) {
                    continue;
                }

                Map<String, Object> outcome = execute(commandId);
                log.info("Scheduled dispatch {}: {}", commandId, outcome.get("decision"));
            } catch (RuntimeException e) {
                log.error("Scheduled dispatch failed for {}", commandId, e);
                deliveryStatuses.update(commandId, "FAILED", e.getMessage());
                // Prevent endless automatic retries of uncertain external side effects.
                pendingCommands.remove(commandId);
                freezeIfPresent(batteryId);
            }
        }
    }

    private boolean isActive(String batteryId) {
        LocalGateway local = localBatteries.get(batteryId);

        if (local == null) {
            return false;
        }

        synchronized (local) {
            return local.active;
        }
    }

    private void freezeIfPresent(String batteryId) {

        if (batteryId != null) {
            freeze(batteryId);
        }
    }

    /** Reinstall gateway state from the ledger; unresolved DISPATCHED outcomes block recovery. */
    public JsonNode reconcile(String batteryId) {
        LocalGateway local = localBatteries.computeIfAbsent(batteryId, ignored -> new LocalGateway());

        synchronized (local) {
            local.active = false;
            JsonNode state = chain.read(BessGuardOperation.READ_COORDINATION_STATE, batteryId);

            if (!"ACTIVE".equals(state.path("mode").asText())) {
                throw new IllegalStateException("Ledger battery is not ACTIVE; gateway remains suspended.");
            }

            for (JsonNode id : state.path("openReservationIds")) {
                JsonNode command = chain.read(BessGuardOperation.READ_DISPATCH, id.asText());

                if ("DISPATCHED".equals(command.path("status").asText())) {
                    throw new IllegalStateException("Dispatched command has no recorded outcome: " + id.asText());
                }
            }

            int version = state.path("activeCertificateVersion").asInt();
            JsonNode cert = chain.read(BessGuardOperation.READ_CERTIFICATE,
                    batteryId, Integer.toString(version));
            bms.install(batteryId, cert.path("maxDischargePowerKw").asDouble(),
                    cert.path("minEnergyKwh").asDouble(), state.path("availableEnergyKwh").asDouble());
            local.version = version;
            local.active = true;
            return state;
        }
    }

    public Map<String, Object> localState(String batteryId) {
        LocalGateway local = localBatteries.get(batteryId);

        if (local == null) {
            return Map.of("batteryId", batteryId, "active", false,
                    "message", "Gateway is not initialized; execution is blocked.");
        }

        synchronized (local) {
            Map<String, Object> result = new HashMap<>(bms.state(batteryId));
            result.put("batteryId", batteryId);
            result.put("active", local.active);
            result.put("version", local.version);
            result.put("stagedCommands", pendingCommands.values().stream()
                    .filter(batteryId::equals).count());
            return result;
        }
    }

    private Map<String, Object> rejected(String commandId, String reason) {
        deliveryStatuses.update(commandId, "REJECTED", reason);
        return Map.of("commandId", commandId, "decision", "REJECTED", "reason", reason);
    }

    private static final class LocalGateway {
        private boolean active;
        private int version;
    }
}
