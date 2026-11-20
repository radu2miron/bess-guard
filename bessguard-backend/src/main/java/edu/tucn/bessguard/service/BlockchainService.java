package edu.tucn.bessguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.model.TransactionRequest;
import edu.tucn.bessguard.model.TxType;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Shared ledger adapter. Fabric submits return only after a VALID commit.
 * Internal queue consumers and schedulers can use this service without an HTTP SecurityContext.
 * User authorization is applied at the REST boundary, never delegated to a RabbitMQ listener.
 */
@Service
public class BlockchainService {

    private final BlockchainProxy blockchainProxy;

    public BlockchainService(BlockchainProxy blockchainProxy) {
        this.blockchainProxy = blockchainProxy;
    }

    public JsonNode submit(BessGuardOperation operation, String... args) {

        if (operation.txType() != TxType.SUBMIT) {
            throw new IllegalArgumentException("Not a submit operation: " + operation);
        }

        return execute(operation, args);
    }

    public JsonNode read(BessGuardOperation operation, String... args) {

        if (operation.txType() != TxType.READ) {
            throw new IllegalArgumentException("Not a read operation: " + operation);
        }

        return execute(operation, args);
    }

    private JsonNode execute(BessGuardOperation operation, String[] args) {
        TransactionRequest request = new TransactionRequest(operation.txType(), operation.function(), args,
                System.currentTimeMillis(), UUID.randomUUID().toString(), "bessguard-backend");
        String result = blockchainProxy.execute(request);
        return JsonMapper.fromJson(result, JsonNode.class);
    }
}
