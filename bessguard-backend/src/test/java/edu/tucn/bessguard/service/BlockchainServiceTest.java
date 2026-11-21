package edu.tucn.bessguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.TransactionRequest;
import edu.tucn.bessguard.model.TxType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
class BlockchainServiceTest {

    @Test
    void supportsMultipleChaincodeArgumentsAndWaitsForCommit() {
        BlockchainProxy proxy = mock(BlockchainProxy.class);
        when(proxy.execute(any())).thenReturn("{\"mode\":\"ACTIVE\"}");
        BlockchainService service = new BlockchainService(proxy);

        JsonNode result = service.submit(BessGuardOperation.ACTIVATE_GATEWAY_VERSION, "bat-1", "2", "15.0");
        assertEquals("ACTIVE", result.path("mode").asText());

        ArgumentCaptor<TransactionRequest> captured = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(proxy).execute(captured.capture());
        assertArrayEquals(new String[]{"bat-1", "2", "15.0"}, captured.getValue().getArgs());
        assertEquals(TxType.SUBMIT, captured.getValue().getTxType());
    }
}
