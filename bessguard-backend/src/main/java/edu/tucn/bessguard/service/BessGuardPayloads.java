package edu.tucn.bessguard.service;

import edu.tucn.bessguard.model.BatteryUnit;
import edu.tucn.bessguard.model.DispatchCommand;
import edu.tucn.bessguard.model.OperatingCertificate;
import edu.tucn.bessguard.model.SecondLifeBESS;

import java.util.UUID;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Client-visible ID defaults; certificate issuers and dispatch statuses are set by chaincode.
 */
public final class BessGuardPayloads {

    private BessGuardPayloads() {
    }

    public static String bess(SecondLifeBESS value) {
        if (value.getId() == null || value.getId().isBlank()) {
            value.setId(UUID.randomUUID().toString());
        }

        value.setCreatedAt(null);
        return JsonMapper.toJson(value);
    }

    public static String battery(BatteryUnit value) {
        if (value.getId() == null || value.getId().isBlank()) {
            value.setId(UUID.randomUUID().toString());
        }

        value.setCreatedAt(null);
        return JsonMapper.toJson(value);
    }

    public static String certificate(OperatingCertificate value) {
        value.setId(null);
        value.setIssuerMspId(null);
        value.setIssuerId(null);
        value.setIssuedAt(null);
        return JsonMapper.toJson(value);
    }

    public static String dispatch(DispatchCommand value) {
        if (value.getId() == null || value.getId().isBlank()) {
            value.setId(UUID.randomUUID().toString());
        }

        // Server-controlled status and audit metadata must never be accepted from clients.
        value.setStatus(null);
        value.setSubmittedAt(null);
        value.setSubmittedBy(null);
        return JsonMapper.toJson(value);
    }

    public static String positiveAttemptId(String attemptId) {
        return attemptId == null || attemptId.isBlank() ? UUID.randomUUID().toString() : attemptId;
    }
}
