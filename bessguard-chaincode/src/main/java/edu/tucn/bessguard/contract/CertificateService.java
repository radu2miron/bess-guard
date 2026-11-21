package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.model.*;
import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;

/**
 * Certificate issuance and recertification; history remains immutable.
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public class CertificateService extends AbstractLedgerService<OperatingCertificate> {
    public CertificateService() {
        super(OperatingCertificate.class, DocType.OPERATING_CERTIFICATE,
                OperatingCertificate::getId, OperatingCertificate::getDocType);
    }

    /**
     * Initial issuance prepares the certificate, but dispatch stays disabled until gateway activation.
     */
    public String issueInitial(Context ctx, String json) {
        DomainSupport.requireTrustedBackend(ctx);
        OperatingCertificate cert = JsonMapper.fromJson(json, OperatingCertificate.class);
        String batteryId = cert == null ? null : DomainSupport.id(cert.getBatteryId(), "batteryId");
        DomainSupport.read(ctx, DocType.BATTERY_UNIT, batteryId, BatteryUnit.class);
        BatteryCoordinationState state = DomainSupport.state(ctx, batteryId);

        if (state.getActiveCertificateVersion() != 0 || state.getPendingCertificateVersion() != null
                || state.getMode() != CoordinationMode.SUSPENDED) {
            throw new IllegalArgumentException("An initial certificate has already been issued.");
        }

        DomainSupport.validateCertificate(cert, batteryId, 1);
        cert.setId(DomainSupport.certificateId(batteryId, 1));
        cert.setIssuerMspId(ctx.getClientIdentity().getMSPID());
        cert.setIssuerId(ctx.getClientIdentity().getId());
        cert.setIssuedAt(DomainSupport.nowText(ctx));
        super.create(ctx, JsonMapper.toJson(cert));
        state.setActiveCertificateVersion(1);
        state.setMode(CoordinationMode.UPDATED);
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(cert);
    }

    /**
     * Freeze new reservations; stage an immutable future certificate.
     */
    public String beginRecertification(Context ctx, String json) {
        DomainSupport.requireTrustedBackend(ctx);
        OperatingCertificate cert = JsonMapper.fromJson(json, OperatingCertificate.class);
        String batteryId = cert == null ? null : DomainSupport.id(cert.getBatteryId(), "batteryId");
        DomainSupport.read(ctx, DocType.BATTERY_UNIT, batteryId, BatteryUnit.class);
        BatteryCoordinationState state = DomainSupport.state(ctx, batteryId);

        if (state.getActiveCertificateVersion() < 1 || state.getPendingCertificateVersion() != null
                || (state.getMode() != CoordinationMode.ACTIVE && state.getMode() != CoordinationMode.SUSPENDED)) {
            throw new IllegalArgumentException("Recertification requires ACTIVE/SUSPENDED state and no pending version.");
        }

        int next = state.getActiveCertificateVersion() + 1;
        DomainSupport.validateCertificate(cert, batteryId, next);
        cert.setId(DomainSupport.certificateId(batteryId, next));
        cert.setIssuerMspId(ctx.getClientIdentity().getMSPID());
        cert.setIssuerId(ctx.getClientIdentity().getId());
        cert.setIssuedAt(DomainSupport.nowText(ctx));
        super.create(ctx, JsonMapper.toJson(cert));
        state.setMode(CoordinationMode.QUIESCING);
        state.setPendingCertificateVersion(next);
        state.setQuiescenceAcknowledgedBy(null);
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(cert);
    }

    /**
     * Gateway attests that old-version operations are stopped and all in-flight commands fenced.
     */
    public String acknowledgeQuiescence(Context ctx, String batteryId) {
        DomainSupport.requireTrustedBackend(ctx);
        BatteryCoordinationState state = DomainSupport.state(ctx, batteryId);

        if (state.getMode() != CoordinationMode.QUIESCING || state.getPendingCertificateVersion() == null) {
            throw new IllegalArgumentException("Battery is not quiescing.");
        }

        if (state.getQuiescenceAcknowledgedBy() != null) {
            throw new IllegalArgumentException("Quiescence already acknowledged.");
        }

        for (String id : state.getOpenReservationIds()) {
            DispatchCommand command = DomainSupport.read(ctx, DocType.DISPATCH_COMMAND, id, DispatchCommand.class);

            if (command.getStatus() == DispatchStatus.DISPATCHED) {
                throw new IllegalArgumentException("Cannot acknowledge while command " + id + " is DISPATCHED; settle outcome first.");
            }

            if (command.getStatus() == DispatchStatus.RESERVED) {
                command.setStatus(DispatchStatus.CANCELLED);
                DomainSupport.put(ctx, DocType.DISPATCH_COMMAND, id, command);
            }
        }
        state.getOpenReservationIds().clear();
        state.setQuiescenceAcknowledgedBy(DomainSupport.caller(ctx));
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(state);
    }

    /**
     * Retry after a failure while a certificate is still staged; gateway must re-acknowledge.
     */
    public String retryQuiescence(Context ctx, String batteryId) {
        DomainSupport.requireTrustedBackend(ctx);
        BatteryCoordinationState state = DomainSupport.state(ctx, batteryId);

        if (state.getMode() != CoordinationMode.SUSPENDED || state.getPendingCertificateVersion() == null) {
            throw new IllegalArgumentException("No suspended, staged recertification to retry.");
        }

        state.setMode(CoordinationMode.QUIESCING);
        state.setQuiescenceAcknowledgedBy(null);
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(state);
    }

    /**
     * Certificate change is atomic against admissions through the shared battery state key.
     */
    public String activateCertificate(Context ctx, String batteryId) {
        DomainSupport.requireTrustedBackend(ctx);
        BatteryCoordinationState state = DomainSupport.state(ctx, batteryId);

        if (state.getMode() != CoordinationMode.QUIESCING || state.getPendingCertificateVersion() == null
                || state.getQuiescenceAcknowledgedBy() == null) {
            throw new IllegalArgumentException("Gateway quiescence acknowledgment is required.");
        }

        if (!state.getOpenReservationIds().isEmpty()) {
            throw new IllegalArgumentException("Open reservations remain.");
        }

        OperatingCertificate cert = DomainSupport.certificate(ctx, batteryId, state.getPendingCertificateVersion());
        DomainSupport.verifyValidNow(cert, DomainSupport.now(ctx));
        state.setActiveCertificateVersion(cert.getVersion());
        state.setPendingCertificateVersion(null);
        state.setMode(CoordinationMode.UPDATED); // gateway must install and acknowledge version
        state.setQuiescenceAcknowledgedBy(null);
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(state);
    }
}
