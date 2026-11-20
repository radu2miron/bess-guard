package edu.tucn.bessguard.model;

import lombok.Data;

/**
 * BESSGuard domain model.
 */
@Data
public class OperatingCertificate {
    private DocType docType = DocType.OPERATING_CERTIFICATE;
    private String id; // batteryId@version, unique and immutable
    private String batteryId;
    private int version;
    private double maxDischargePowerKw;
    private double minEnergyKwh;
    private double maxEnergyKwh;
    private String validFrom;
    private String validUntil;
    private String issuerMspId;
    private String issuerId;
    private String issuedAt;
    private String evidenceRef;
}
