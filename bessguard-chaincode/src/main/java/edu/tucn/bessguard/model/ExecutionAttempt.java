package edu.tucn.bessguard.model;

import lombok.Data;

/**
 * BESSGuard domain model.
 */
@Data
public class ExecutionAttempt {
    private DocType docType = DocType.EXECUTION_ATTEMPT;
    private String id; // commandId@attemptId
    private String commandId;
    private String attemptId;
    private Decision decision;
    private String reason;
    private String evaluatedAt;
    private String gatewayIdentity;
    private ExecutionOutcome outcome = ExecutionOutcome.NOT_REPORTED;
    private Double deliveredEnergyKwh;
    private String reportedAt;
}
