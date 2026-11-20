package edu.tucn.bessguard.service;

import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.DispatchCommand;
import edu.tucn.bessguard.model.DispatchMessage;
import edu.tucn.bessguard.model.DispatchSubmissionResponse;
import edu.tucn.bessguard.queue.DispatchMessagePublisher;
import edu.tucn.bessguard.security.RoleAuthorizationService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnergyManagementServiceTest {

    @Test
    void publishesOnlyAfterValidReservationCommit() {
        BlockchainService ledger = mock(BlockchainService.class);
        DispatchMessagePublisher publisher = mock(DispatchMessagePublisher.class);
        DeliveryStatusService statuses = mock(DeliveryStatusService.class);
        RoleAuthorizationService roles = mock(RoleAuthorizationService.class);
        when(ledger.submit(eq(BessGuardOperation.RESERVE_DISPATCH), any(String.class)))
                .thenReturn(JsonMapper.fromJson("{\"id\":\"d1\",\"batteryId\":\"b1\",\"status\":\"RESERVED\"}",
                        com.fasterxml.jackson.databind.JsonNode.class));
        EnergyManagementService ems = new EnergyManagementService(ledger, publisher, statuses, roles);
        DispatchCommand command = new DispatchCommand();
        command.setId("d1");
        command.setBatteryId("b1");

        DispatchSubmissionResponse response = ems.reserve(command);
        assertEquals("QUEUED", response.deliveryState());

        InOrder order = inOrder(ledger, publisher);
        order.verify(ledger).submit(eq(BessGuardOperation.RESERVE_DISPATCH), any(String.class));
        order.verify(publisher).publish(any(DispatchMessage.class));
        verify(roles).authorize(BessGuardOperation.RESERVE_DISPATCH);
    }

    @Test
    void neverPublishesIfCommitFails() {
        BlockchainService ledger = mock(BlockchainService.class);
        DispatchMessagePublisher publisher = mock(DispatchMessagePublisher.class);
        when(ledger.submit(eq(BessGuardOperation.RESERVE_DISPATCH), any(String.class)))
                .thenThrow(new IllegalStateException("Fabric commit failed"));
        EnergyManagementService ems = new EnergyManagementService(ledger, publisher,
                mock(DeliveryStatusService.class), mock(RoleAuthorizationService.class));

        assertThrows(IllegalStateException.class, () -> ems.reserve(new DispatchCommand()));
        verifyNoInteractions(publisher);
    }

    @Test
    void reportsCommittedButDeliveryPendingWhenRabbitFails() {
        BlockchainService ledger = mock(BlockchainService.class);
        DispatchMessagePublisher publisher = mock(DispatchMessagePublisher.class);
        when(ledger.submit(eq(BessGuardOperation.RESERVE_DISPATCH), any(String.class)))
                .thenReturn(JsonMapper.fromJson("{\"id\":\"d1\",\"batteryId\":\"b1\",\"status\":\"RESERVED\"}",
                        com.fasterxml.jackson.databind.JsonNode.class));
        doThrow(new RuntimeException("rabbit down")).when(publisher).publish(any());
        EnergyManagementService ems = new EnergyManagementService(ledger, publisher,
                mock(DeliveryStatusService.class), mock(RoleAuthorizationService.class));

        DispatchSubmissionResponse response = ems.reserve(new DispatchCommand());
        assertEquals("DELIVERY_PENDING", response.deliveryState());
        assertEquals("RESERVED", response.reservation().path("status").asText());
    }
}
