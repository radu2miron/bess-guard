package edu.tucn.bessguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.DispatchCommand;
import edu.tucn.bessguard.model.DispatchMessage;
import edu.tucn.bessguard.model.DispatchSubmissionResponse;
import edu.tucn.bessguard.queue.DispatchMessagePublisher;
import edu.tucn.bessguard.model.dto.PeakShavingRequest;
import edu.tucn.bessguard.model.dto.PeakShavingResponse;
import edu.tucn.bessguard.security.RoleAuthorizationService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * EMS -> Fabric reservation commit -> RabbitMQ dispatch notification.
 * Never publishes a command before its reservation has been committed VALID.
 */
@Service
@Log4j2
public class EnergyManagementService {

    private final BlockchainService blockchain;
    private final DispatchMessagePublisher publisher;
    private final DeliveryStatusService statuses;
    private final RoleAuthorizationService roles;

    public EnergyManagementService(BlockchainService blockchain, DispatchMessagePublisher publisher,
                                   DeliveryStatusService statuses, RoleAuthorizationService roles) {
        this.blockchain = blockchain;
        this.publisher = publisher;
        this.statuses = statuses;
        this.roles = roles;
    }

    public DispatchSubmissionResponse reserve(DispatchCommand command) {
        roles.authorize(BessGuardOperation.RESERVE_DISPATCH);

        JsonNode committed = blockchain.submit(BessGuardOperation.RESERVE_DISPATCH,
                BessGuardPayloads.dispatch(command));
        return publishCommitted(committed);
    }

    /**
     * Minimal greedy peak-shaving EMS. It makes independent ledger reservations in sequence;
     * it is not an atomic multi-battery optimizer and cannot replace local BMS checks.
     */
    public PeakShavingResponse planPeakShaving(PeakShavingRequest request) {
        roles.authorize(BessGuardOperation.RESERVE_DISPATCH);

        if (request.batteryIds() == null || request.batteryIds().isEmpty()
                || !Double.isFinite(request.requestedPowerKw()) || request.requestedPowerKw() <= 0) {
            throw new IllegalArgumentException("Battery IDs and positive requested power are required.");
        }

        Instant start = Instant.parse(request.startTime());
        Instant end = Instant.parse(request.endTime());
        double hours = Duration.between(start, end).toMillis() / 3_600_000.0;

        if (hours <= 0 || !start.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Scheduling interval must start in the future and have positive duration.");
        }

        double remaining = request.requestedPowerKw();
        List<DispatchSubmissionResponse> reservations = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String batteryId : request.batteryIds()) {

            if (remaining <= 1e-9) {
                break;
            }

            try {
                JsonNode state = blockchain.read(BessGuardOperation.READ_COORDINATION_STATE, batteryId);

                if (!"ACTIVE".equals(state.path("mode").asText())) {
                    skipped.add(batteryId + ": not ACTIVE");
                    continue;
                }

                int version = state.path("activeCertificateVersion").asInt();
                JsonNode cert = blockchain.read(BessGuardOperation.READ_CERTIFICATE, batteryId,
                        Integer.toString(version));
                double usableKwh = Math.max(0, state.path("availableEnergyKwh").asDouble()
                        - cert.path("minEnergyKwh").asDouble());
                double power = Math.min(remaining,
                        Math.min(cert.path("maxDischargePowerKw").asDouble(), usableKwh / hours));

                if (power <= 1e-9) {
                    skipped.add(batteryId + ": no remaining certified flexibility");
                    continue;
                }

                DispatchCommand dispatch = new DispatchCommand();
                dispatch.setId(UUID.randomUUID().toString());
                dispatch.setBatteryId(batteryId);
                dispatch.setCertificateVersion(version);
                dispatch.setStartTime(request.startTime());
                dispatch.setEndTime(request.endTime());
                dispatch.setExpiresAt(request.expiresAt());
                dispatch.setRequestedPowerKw(power);
                reservations.add(reserve(dispatch));
                remaining -= power;
            } catch (RuntimeException e) {
                skipped.add(batteryId + ": " + e.getMessage());
            }
        }

        return new PeakShavingResponse(request.requestedPowerKw(), request.requestedPowerKw() - remaining,
                remaining, List.copyOf(reservations), List.copyOf(skipped));
    }

    /** Re-deliver an already committed reservation after a broker outage or backend restart. */
    public DispatchSubmissionResponse redeliver(String commandId) {
        roles.authorize(BessGuardOperation.RESERVE_DISPATCH);
        JsonNode committed = blockchain.read(BessGuardOperation.READ_DISPATCH, commandId);
        return publishCommitted(committed);
    }

    private DispatchSubmissionResponse publishCommitted(JsonNode reservation) {
        String id = reservation.path("id").asText();
        String batteryId = reservation.path("batteryId").asText();

        if (!"RESERVED".equals(reservation.path("status").asText())) {
            throw new IllegalStateException("Only a committed RESERVED command may be delivered.");
        }

        DispatchMessage message = new DispatchMessage(id, batteryId, System.currentTimeMillis());

        try {
            publisher.publish(message);
            statuses.update(id, "QUEUED", "Reservation committed; RabbitMQ delivery requested.");
            return new DispatchSubmissionResponse(reservation, "QUEUED",
                    "Reservation committed. RabbitMQ dispatch delivery is pending.");
        } catch (RuntimeException e) {
            statuses.update(id, "DELIVERY_PENDING", "Reservation committed, but RabbitMQ publication failed.");
            log.error("Reservation {} committed, but dispatch notification could not be published", id, e);
            return new DispatchSubmissionResponse(reservation, "DELIVERY_PENDING",
                    "Reservation is committed, but RabbitMQ publication failed. Redeliver this command.");
        }
    }
}
