package edu.tucn.bessguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import edu.tucn.bessguard.model.BessGuardOperation;
import edu.tucn.bessguard.security.RoleAuthorizationService;
import org.springframework.stereotype.Service;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Role-checked application entry point for direct HTTP-to-ledger operations.
 */
@Service
public class AuthorizedBlockchainService {

    private final BlockchainService blockchain;
    private final RoleAuthorizationService roles;

    public AuthorizedBlockchainService(BlockchainService blockchain, RoleAuthorizationService roles) {
        this.blockchain = blockchain;
        this.roles = roles;
    }

    public JsonNode submit(BessGuardOperation operation, String... args) {
        roles.authorize(operation);
        return blockchain.submit(operation, args);
    }

    public JsonNode read(BessGuardOperation operation, String... args) {
        roles.authorize(operation);
        return blockchain.read(operation, args);
    }
}
