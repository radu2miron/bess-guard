package edu.tucn.bessguard.model;

import lombok.Data;

/**
 * BESSGuard domain model.
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Data
public class BatteryCoordinationState {
    private DocType docType = DocType.BATTERY_COORDINATION_STATE;
    private String id; // same as batteryId; this is the per-battery MVCC guard key
    private int activeCertificateVersion;
    private Integer pendingCertificateVersion;
    private CoordinationMode mode = CoordinationMode.SUSPENDED;
    private long revision;
    private double availableEnergyKwh; // gateway-supplied snapshot, not safety-critical SOC
    private java.util.List<String> openReservationIds = new java.util.ArrayList<>();
    private String lastUpdatedAt;
    private String quiescenceAcknowledgedBy;
}
