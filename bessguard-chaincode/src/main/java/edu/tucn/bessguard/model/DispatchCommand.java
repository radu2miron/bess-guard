package edu.tucn.bessguard.model;

import lombok.Data;

/**
 * BESSGuard domain model.
 */
@Data
public class DispatchCommand {
    private DocType docType = DocType.DISPATCH_COMMAND;
    private String id;
    private String batteryId;
    private int certificateVersion;
    private String startTime;
    private String endTime;
    private String expiresAt;
    private double requestedPowerKw; // piecewise-constant discharge during [start,end)
    private DispatchStatus status;
    private String submittedAt;
    private String submittedBy;
}
