package edu.tucn.bessguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.DispatchMessage;
import edu.tucn.bessguard.model.dto.GatewayActivationRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
class SimulatedGatewayServiceTest {

    @Test
    void rejectsStagedOldVersionBeforeAnyFabricExecutionAuthorization() {
        BlockchainService ledger = mock(BlockchainService.class);
        SimulatedBmsService bms = new SimulatedBmsService();
        SimulatedGatewayService gateway = new SimulatedGatewayService(ledger, bms, new DeliveryStatusService());
        String future = Instant.now().plusSeconds(900).toString();
        String end = Instant.now().plusSeconds(1800).toString();
        JsonNode command = JsonMapper.fromJson("{\"id\":\"d1\",\"batteryId\":\"b1\","
                + "\"certificateVersion\":1,\"status\":\"RESERVED\",\"requestedPowerKw\":4,"
                + "\"startTime\":\"" + future + "\",\"endTime\":\"" + end + "\","
                + "\"expiresAt\":\"" + end + "\"}", JsonNode.class);
        when(ledger.read(BessGuardOperation.READ_DISPATCH, "d1")).thenReturn(command);
        when(ledger.read(BessGuardOperation.READ_COORDINATION_STATE, "b1"))
                .thenReturn(JsonMapper.fromJson("{\"mode\":\"ACTIVE\",\"activeCertificateVersion\":1}", JsonNode.class));
        when(ledger.read(BessGuardOperation.READ_CERTIFICATE, "b1", "2"))
                .thenReturn(JsonMapper.fromJson("{\"maxDischargePowerKw\":6,\"minEnergyKwh\":2}", JsonNode.class));
        when(ledger.submit(BessGuardOperation.ACTIVATE_GATEWAY_VERSION, "b1", "2", "15.0"))
                .thenReturn(JsonMapper.fromJson("{\"mode\":\"ACTIVE\"}", JsonNode.class));

        assertTrue(gateway.stage(new DispatchMessage("d1", "b1", System.currentTimeMillis())));
        gateway.activate("b1", new GatewayActivationRequest(2, 15.0));
        Map<String, Object> response = gateway.execute("d1");

        assertEquals("REJECTED", response.get("decision"));
        verify(ledger, never()).submit(eq(BessGuardOperation.AUTHORIZE_EXECUTION), any(String.class), any(String.class));
    }
}
