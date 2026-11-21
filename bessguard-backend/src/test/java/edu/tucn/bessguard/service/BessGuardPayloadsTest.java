package edu.tucn.bessguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.DispatchCommand;
import edu.tucn.bessguard.model.DispatchStatus;
import edu.tucn.bessguard.model.OperatingCertificate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
class BessGuardPayloadsTest {

    @Test
    void dispatchStatusCannotBeInjectedByClient() {
        DispatchCommand command = new DispatchCommand();
        command.setBatteryId("battery-1");
        command.setStatus(DispatchStatus.COMPLETED);
        command.setSubmittedBy("forged-issuer");

        JsonNode json = JsonMapper.fromJson(BessGuardPayloads.dispatch(command), JsonNode.class);
        assertTrue(json.hasNonNull("id"));
        assertFalse(json.hasNonNull("status"));
        assertFalse(json.hasNonNull("submittedBy"));
    }

    @Test
    void certificateIssuerIsEstablishedByFabricChaincode() {
        OperatingCertificate cert = new OperatingCertificate();
        cert.setBatteryId("battery-1");
        cert.setIssuerMspId("FORGED_MSP");
        cert.setIssuerId("FORGED_USER");

        JsonNode json = JsonMapper.fromJson(BessGuardPayloads.certificate(cert), JsonNode.class);
        assertFalse(json.hasNonNull("issuerMspId"));
        assertFalse(json.hasNonNull("issuerId"));
    }
}
