package edu.tucn.bessguard.security;

import edu.tucn.bessguard.exceptions.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Backend-owned role assignments. Client-supplied JSON and JWT payloads cannot grant roles.
 * Configured passwords are for the experimental deployment, not a production user database.
 */
@Service
public class UserAccountService {

    private final Map<String, Account> accounts = new HashMap<>();

    public UserAccountService(
            @Value("${security.accounts.admin.username:user1}") String adminUser,
            @Value("${security.accounts.admin.password:pwd1}") String adminPassword,
            @Value("${security.accounts.certifier.username:certifier}") String certifierUser,
            @Value("${security.accounts.certifier.password:}") String certifierPassword,
            @Value("${security.accounts.ems.username:ems}") String emsUser,
            @Value("${security.accounts.ems.password:}") String emsPassword,
            @Value("${security.accounts.gateway.username:gateway}") String gatewayUser,
            @Value("${security.accounts.gateway.password:}") String gatewayPassword) {

        addAccount(adminUser, adminPassword, "ADMIN");
        addAccount(certifierUser, certifierPassword, "CERTIFIER");
        addAccount(emsUser, emsPassword, "EMS");
        addAccount(gatewayUser, gatewayPassword, "GATEWAY");
    }

    private void addAccount(String username, String password, String role) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return;
        }

        if (accounts.putIfAbsent(username, new Account(password, role)) != null) {
            throw new IllegalArgumentException("Duplicate configured account: " + username);
        }
    }

    public void authenticate(String username, String password) {
        Account account = accounts.get(username);

        if (account == null || password == null
                || !MessageDigest.isEqual(account.password().getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Bad credentials");
        }
    }

    public String roleFor(String username) {
        Account account = accounts.get(username);
        return account == null ? null : account.role();
    }

    private record Account(String password, String role) {
    }
}
