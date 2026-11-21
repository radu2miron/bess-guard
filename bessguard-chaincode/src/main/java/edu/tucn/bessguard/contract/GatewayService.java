package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.model.*;
import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;

/**
 * Ledger acknowledgments from the trusted dispatch gateway (local fencing is mandatory).
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public class GatewayService {
    /**
     * Call only AFTER the gateway installs the limits and closes its prior-version fence.
     */
    public String activateGatewayVersion(Context ctx, String batteryId, String versionText, String energyText) {
        DomainSupport.requireTrustedBackend(ctx);
        int version = Integer.parseInt(versionText);
        double energy = DomainSupport.finiteNonnegative(Double.parseDouble(energyText), "availableEnergyKwh");
        BatteryCoordinationState state = DomainSupport.state(ctx, batteryId);

        if (state.getMode() != CoordinationMode.UPDATED || state.getActiveCertificateVersion() != version) {
            throw new IllegalArgumentException("Gateway activation version does not match UPDATED certificate.");
        }

        OperatingCertificate cert = DomainSupport.certificate(ctx, batteryId, version);
        DomainSupport.verifyValidNow(cert, DomainSupport.now(ctx));

        if (energy < cert.getMinEnergyKwh() || energy > cert.getMaxEnergyKwh()) {
            throw new IllegalArgumentException("Reported energy is outside the certificate energy bounds.");
        }

        state.setAvailableEnergyKwh(energy);
        state.setMode(CoordinationMode.ACTIVE);
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(state);
    }

    /**
     * Gateway-generated energy estimate; ledger is not a substitute for real-time BMS checks.
     */
    public String reportAvailableEnergy(Context ctx, String batteryId, String energyText) {
        DomainSupport.requireTrustedBackend(ctx);
        double energy = DomainSupport.finiteNonnegative(Double.parseDouble(energyText), "availableEnergyKwh");
        BatteryCoordinationState state = DomainSupport.state(ctx, batteryId);

        if (state.getActiveCertificateVersion() == 0) {
            throw new IllegalArgumentException("No certificate issued.");
        }

        OperatingCertificate cert = DomainSupport.certificate(ctx, batteryId, state.getActiveCertificateVersion());

        if (energy > cert.getMaxEnergyKwh() || (state.getMode() == CoordinationMode.ACTIVE && energy < cert.getMinEnergyKwh())) {
            throw new IllegalArgumentException("Energy is outside active operating bounds.");
        }

        state.setAvailableEnergyKwh(energy);

        if (state.getMode() == CoordinationMode.ACTIVE) {
            double outstandingKwh = 0;

            for (String commandId : state.getOpenReservationIds()) {
                DispatchCommand command = DomainSupport.read(ctx, DocType.DISPATCH_COMMAND,
                        commandId, DispatchCommand.class);
                if (DomainSupport.reserving(command)) {
                    outstandingKwh += DomainSupport.requiredEnergy(command);
                }
            }

            if (energy - cert.getMinEnergyKwh() + 1e-9 < outstandingKwh) {
                // External capacity loss invalidates the future energy budget. Fail closed.
                state.setMode(CoordinationMode.SUSPENDED);
            }
        }

        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(state);
    }

    /**
     * Emergency coordination suspension; local BMS must intervene independently.
     */
    public String suspendDispatch(Context ctx, String batteryId) {
        DomainSupport.requireTrustedBackend(ctx);
        BatteryCoordinationState state = DomainSupport.state(ctx, batteryId);

        if (state.getMode() == CoordinationMode.REVOKED) {
            throw new IllegalArgumentException("Battery is revoked.");
        }

        state.setMode(CoordinationMode.SUSPENDED);
        DomainSupport.saveState(ctx, state);

        return JsonMapper.toJson(state);
    }
}
