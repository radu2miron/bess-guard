package edu.tucn.bessguard.security;

import edu.tucn.bessguard.model.BessGuardOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoleAuthorizationServiceTest {

    private final RoleAuthorizationService guard = new RoleAuthorizationService(true);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emsCanReserveButCannotIssueCertificate() {
        login("ems", "EMS");
        assertEquals("ems", guard.authorize(BessGuardOperation.RESERVE_DISPATCH));
        assertThrows(AccessDeniedException.class,
                () -> guard.authorize(BessGuardOperation.ISSUE_INITIAL_CERTIFICATE));
    }

    @Test
    void administratorMayInvokeApplicationOperations() {
        login("user1", "ADMIN");
        assertEquals("user1", guard.authorize(BessGuardOperation.ISSUE_INITIAL_CERTIFICATE));
        assertEquals("user1", guard.authorize(BessGuardOperation.AUTHORIZE_EXECUTION));
    }

    @Test
    void noAuthenticationIsDenied() {
        assertThrows(AccessDeniedException.class,
                () -> guard.authorize(BessGuardOperation.REGISTER_BESS));
    }

    private void login(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
