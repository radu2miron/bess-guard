package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.model.*;
import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Atomic, version-aware dispatch reservation logic.
 */
public class DispatchService extends AbstractLedgerService<DispatchCommand> {
    public DispatchService() {
        super(DispatchCommand.class, DocType.DISPATCH_COMMAND, DispatchCommand::getId, DispatchCommand::getDocType);
    }

    /**
     * Validated reservation is final only if the enclosing Fabric transaction commits VALID.
     */
    public String reserve(Context ctx, String json) {
        DomainSupport.requireTrustedBackend(ctx);
        DispatchCommand command = JsonMapper.fromJson(json, DispatchCommand.class);

        if (command == null || command.getDocType() != DocType.DISPATCH_COMMAND) {
            throw new IllegalArgumentException("Invalid dispatch docType.");
        }

        DomainSupport.id(command.getId(), "commandId");
        DomainSupport.id(command.getBatteryId(), "batteryId");

        if (command.getStatus() != null) {
            throw new IllegalArgumentException("status is managed by the chaincode.");
        }

        if (DomainSupport.exists(ctx, DocType.DISPATCH_COMMAND, command.getId())) {
            throw new IllegalArgumentException("Duplicate commandId: " + command.getId());
        }

        Instant now = DomainSupport.now(ctx);
        Instant start = DomainSupport.instant(command.getStartTime(), "startTime");
        Instant end = DomainSupport.instant(command.getEndTime(), "endTime");
        Instant expiration = DomainSupport.instant(command.getExpiresAt(), "expiresAt");

        if (start.isBefore(now) || !start.isBefore(end) || !now.isBefore(expiration) || !expiration.isAfter(start)) {
            throw new IllegalArgumentException("Dispatch must start in future, have a positive interval and be unexpired.");
        }

        DomainSupport.finitePositive(command.getRequestedPowerKw(), "requestedPowerKw");
        BatteryCoordinationState state = DomainSupport.state(ctx, command.getBatteryId());

        if (state.getMode() != CoordinationMode.ACTIVE || state.getActiveCertificateVersion() != command.getCertificateVersion()) {
            throw new IllegalArgumentException("Battery is not ACTIVE or certificate version is stale.");
        }

        OperatingCertificate cert = DomainSupport.certificate(ctx, command.getBatteryId(), state.getActiveCertificateVersion());
        DomainSupport.verifyValidNow(cert, now);

        if (start.isBefore(DomainSupport.instant(cert.getValidFrom(), "validFrom"))
                || end.isAfter(DomainSupport.instant(cert.getValidUntil(), "validUntil"))) {
            throw new IllegalArgumentException("Dispatch interval falls outside certificate validity.");
        }

        if (command.getRequestedPowerKw() > cert.getMaxDischargePowerKw()) {
            throw new IllegalArgumentException("Requested power exceeds certified maximum.");
        }

        command.setStatus(DispatchStatus.RESERVED);
        // Eliminate completed/expired reservations in this SAME transaction, using explicit keys
        // rather than CouchDB rich queries (which can suffer phantom-read anomalies).
        List<DispatchCommand> accepted = new ArrayList<>();
        List<String> remaining = new ArrayList<>();

        for (String existingId : state.getOpenReservationIds()) {
            DispatchCommand existing = read(ctx, existingId);

            if (!DomainSupport.reserving(existing)) {
                continue;
            }

            if (existing.getStatus() == DispatchStatus.RESERVED
                    && (!now.isBefore(DomainSupport.instant(existing.getExpiresAt(), "expiresAt"))
                    || !now.isBefore(DomainSupport.instant(existing.getEndTime(), "endTime")))) {
                existing.setStatus(DispatchStatus.EXPIRED);
                DomainSupport.put(ctx, DocType.DISPATCH_COMMAND, existing.getId(), existing);
                continue;
            }

            accepted.add(existing);
            remaining.add(existingId);
        }

        ensureFeasible(command, accepted, cert, state);
        command.setSubmittedAt(DomainSupport.nowText(ctx));
        command.setSubmittedBy(DomainSupport.caller(ctx));
        super.create(ctx, JsonMapper.toJson(command));
        remaining.add(command.getId());
        state.setOpenReservationIds(remaining);
        DomainSupport.saveState(ctx, state); // read/write-set conflict with recertification and concurrent reservations

        return JsonMapper.toJson(command);
    }

    private static void ensureFeasible(DispatchCommand candidate, List<DispatchCommand> others,
                                       OperatingCertificate cert, BatteryCoordinationState state) {
        // Conservative energy budget: all still-open reservations consume future energy.
        double energy = DomainSupport.requiredEnergy(candidate);
        TreeSet<Instant> boundaries = new TreeSet<>();
        boundaries.add(DomainSupport.instant(candidate.getStartTime(), "startTime"));
        boundaries.add(DomainSupport.instant(candidate.getEndTime(), "endTime"));

        for (DispatchCommand other : others) {
            energy += DomainSupport.requiredEnergy(other);
            boundaries.add(DomainSupport.instant(other.getStartTime(), "startTime"));
            boundaries.add(DomainSupport.instant(other.getEndTime(), "endTime"));
        }

        if (energy > state.getAvailableEnergyKwh() - cert.getMinEnergyKwh() + 1e-9) {
            throw new IllegalArgumentException("Insufficient available energy for outstanding reservations.");
        }
        // Piecewise-constant powers change only at endpoints: evaluate all half-open intervals.
        for (Instant t : boundaries) {
            double total = 0;

            if (activeAt(candidate, t)) {
                total += candidate.getRequestedPowerKw();
            }

            for (DispatchCommand other : others) {
                if (activeAt(other, t)) {
                    total += other.getRequestedPowerKw();
                }
            }

            if (total > cert.getMaxDischargePowerKw() + 1e-9) {
                throw new IllegalArgumentException("Concurrent reservations exceed certified discharge power.");
            }
        }
    }

    private static boolean activeAt(DispatchCommand command, Instant t) {
        return !t.isBefore(DomainSupport.instant(command.getStartTime(), "startTime"))
                && t.isBefore(DomainSupport.instant(command.getEndTime(), "endTime"));
    }

    public String cancel(Context ctx, String commandId) {
        DomainSupport.requireTrustedBackend(ctx);
        DispatchCommand command = read(ctx, DomainSupport.id(commandId, "commandId"));

        if (command.getStatus() != DispatchStatus.RESERVED) {
            throw new IllegalArgumentException("Only RESERVED commands may be cancelled.");
        }

        BatteryCoordinationState state = DomainSupport.state(ctx, command.getBatteryId());
        command.setStatus(DispatchStatus.CANCELLED);
        DomainSupport.put(ctx, DocType.DISPATCH_COMMAND, command.getId(), command);
        state.getOpenReservationIds().remove(command.getId());
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(command);
    }
}
