package edu.tucn.bessguard.queue;

import edu.tucn.bessguard.model.DispatchMessage;
import edu.tucn.bessguard.service.DeliveryStatusService;
import edu.tucn.bessguard.service.SimulatedGatewayService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class DispatchMessageListenerTest {

    @Test
    void deliversToGatewayWithoutSubmittingNewFabricTransactions() {
        SimulatedGatewayService gateway = mock(SimulatedGatewayService.class);
        DeliveryStatusService statuses = mock(DeliveryStatusService.class);
        DispatchMessage message = new DispatchMessage("d1", "b1", System.currentTimeMillis());
        when(gateway.stage(message)).thenReturn(true);

        new DispatchMessageListener(gateway, statuses).receiveDispatch(message);

        verify(gateway).stage(message);
        verify(statuses).update("d1", "STAGED", "Queued command received by gateway.");
    }
}
