package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.model.*;
import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;

import java.time.Instant;

/**
 * Gateway-side authorization ledger and execution evidence. Not a physical controller.
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public class ExecutionService extends AbstractLedgerService<ExecutionAttempt> {
    public ExecutionService() {
        super(ExecutionAttempt.class, DocType.EXECUTION_ATTEMPT,
                ExecutionAttempt::getId, ExecutionAttempt::getDocType);
    }

    /**
     * The gateway must wait for a VALID commit and re-check its LOCAL fence before actuating.
     */
    public String authorize(Context ctx, String commandId, String attemptId) {
        DomainSupport.requireTrustedBackend(ctx);
        DomainSupport.id(commandId, "commandId");
        String uniqueId = DomainSupport.attemptId(commandId, attemptId);

        if (DomainSupport.exists(ctx, DocType.EXECUTION_ATTEMPT, uniqueId)) {
            throw new IllegalArgumentException("Attempt id already used; replay is not an authorization.");
        }

        DispatchCommand command = DomainSupport.read(ctx, DocType.DISPATCH_COMMAND, commandId, DispatchCommand.class);
        BatteryCoordinationState state = DomainSupport.state(ctx, command.getBatteryId());
        String reason = rejectionReason(ctx, command, state);
        ExecutionAttempt attempt = new ExecutionAttempt();
        attempt.setId(uniqueId);
        attempt.setCommandId(commandId);
        attempt.setAttemptId(attemptId);
        attempt.setGatewayIdentity(DomainSupport.caller(ctx));
        attempt.setEvaluatedAt(DomainSupport.nowText(ctx));

        if (reason == null) {
            attempt.setDecision(Decision.ACCEPTED);
            attempt.setReason("Version and operational checks passed; local BMS verification still required.");
            command.setStatus(DispatchStatus.DISPATCHED);
            DomainSupport.put(ctx, DocType.DISPATCH_COMMAND, commandId, command);
        } else {
            attempt.setDecision(Decision.REJECTED);
            attempt.setReason(reason);

            if (command.getStatus() == DispatchStatus.RESERVED) {
                Instant now = DomainSupport.now(ctx);
                // A premature delivery is a rejected ATTEMPT, not a cancelled reservation.
                if (!now.isBefore(DomainSupport.instant(command.getStartTime(), "startTime"))) {
                    if (!now.isBefore(DomainSupport.instant(command.getEndTime(), "endTime"))
                            || !now.isBefore(DomainSupport.instant(command.getExpiresAt(), "expiresAt"))) {
                        command.setStatus(DispatchStatus.EXPIRED);
                    } else {
                        command.setStatus(DispatchStatus.REJECTED);
                    }

                    DomainSupport.put(ctx, DocType.DISPATCH_COMMAND, commandId, command);
                    state.getOpenReservationIds().remove(commandId);
                }
            }
        }

        super.create(ctx, JsonMapper.toJson(attempt));
        DomainSupport.saveState(ctx, state); // races with certificate updates are MVCC-checked

        return JsonMapper.toJson(attempt);
    }

    private String rejectionReason(Context ctx, DispatchCommand command, BatteryCoordinationState state) {
        Instant now = DomainSupport.now(ctx);

        if (command.getStatus() != DispatchStatus.RESERVED) {
            return "Command is not RESERVED.";
        }

        if (state.getMode() != CoordinationMode.ACTIVE) {
            return "Battery is not ACTIVE.";
        }

        if (command.getCertificateVersion() != state.getActiveCertificateVersion()) {
            return "Stale certificate version.";
        }

        if (now.isBefore(DomainSupport.instant(command.getStartTime(), "startTime"))
                || !now.isBefore(DomainSupport.instant(command.getEndTime(), "endTime"))
                || !now.isBefore(DomainSupport.instant(command.getExpiresAt(), "expiresAt"))) {
            return "Command is outside its execution window.";
        }

        OperatingCertificate cert = DomainSupport.certificate(ctx, command.getBatteryId(), command.getCertificateVersion());

        if (now.isBefore(DomainSupport.instant(cert.getValidFrom(), "validFrom"))
                || !now.isBefore(DomainSupport.instant(cert.getValidUntil(), "validUntil"))) {
            return "Certificate validity expired.";
        }

        if (command.getRequestedPowerKw() > cert.getMaxDischargePowerKw()
                || state.getAvailableEnergyKwh() - cert.getMinEnergyKwh() + 1e-9
                < DomainSupport.requiredEnergy(command)) {
            return "Operating limits not satisfied.";
        }

        return null;
    }

    /**
     * Record outcome after local execution; ACCEPTED here is never proof of physical delivery.
     */
    public String reportOutcome(Context ctx, String commandId, String attemptId, String outcomeText, String deliveredKwhText) {
        DomainSupport.requireTrustedBackend(ctx);
        ExecutionOutcome outcome = ExecutionOutcome.valueOf(outcomeText);

        if (outcome == ExecutionOutcome.NOT_REPORTED) {
            throw new IllegalArgumentException("Terminal outcome required.");
        }

        String id = DomainSupport.attemptId(commandId, attemptId);
        ExecutionAttempt attempt = read(ctx, id);

        if (attempt.getDecision() != Decision.ACCEPTED || attempt.getOutcome() != ExecutionOutcome.NOT_REPORTED) {
            throw new IllegalArgumentException("Execution attempt is not awaiting an outcome.");
        }

        DispatchCommand command = DomainSupport.read(ctx, DocType.DISPATCH_COMMAND, commandId, DispatchCommand.class);

        if (command.getStatus() != DispatchStatus.DISPATCHED) {
            throw new IllegalArgumentException("Command is not DISPATCHED.");
        }

        if (!DomainSupport.caller(ctx).equals(attempt.getGatewayIdentity())) {
            throw new SecurityException("Only the same gateway identity may report this attempt.");
        }

        double delivered = DomainSupport.finiteNonnegative(Double.parseDouble(deliveredKwhText), "deliveredEnergyKwh");

        if (delivered > DomainSupport.requiredEnergy(command) + 1e-9) {
            throw new IllegalArgumentException("Reported delivery exceeds reserved energy.");
        }

        attempt.setOutcome(outcome);
        attempt.setDeliveredEnergyKwh(delivered);
        attempt.setReportedAt(DomainSupport.nowText(ctx));
        command.setStatus(outcome == ExecutionOutcome.COMPLETED ? DispatchStatus.COMPLETED : DispatchStatus.FAILED);
        BatteryCoordinationState state = DomainSupport.state(ctx, command.getBatteryId());
        state.getOpenReservationIds().remove(commandId);
        state.setAvailableEnergyKwh(Math.max(0, state.getAvailableEnergyKwh() - delivered));
        OperatingCertificate cert = DomainSupport.certificate(ctx, command.getBatteryId(), state.getActiveCertificateVersion());

        if (state.getMode() == CoordinationMode.ACTIVE && state.getAvailableEnergyKwh() < cert.getMinEnergyKwh()) {
            state.setMode(CoordinationMode.SUSPENDED);
        }

        DomainSupport.put(ctx, DocType.DISPATCH_COMMAND, commandId, command);
        DomainSupport.put(ctx, DocType.EXECUTION_ATTEMPT, id, attempt);
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(attempt);
    }
}
