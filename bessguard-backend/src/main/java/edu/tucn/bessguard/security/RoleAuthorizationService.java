package edu.tucn.bessguard.security;

import edu.tucn.bessguard.model.BessGuardOperation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Service
public class RoleAuthorizationService {

    private final boolean enabled;

    public RoleAuthorizationService(@Value("${authorization.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public String authorize(BessGuardOperation operation) {
        if (!enabled) {
            return "local-dev";
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required.");
        }

        String requiredRole = operation.requiredRole();

        if (requiredRole != null && auth.getAuthorities().stream().noneMatch(authority ->
                authority.getAuthority().equals("ROLE_ADMIN")
                        || authority.getAuthority().equals("ROLE_" + requiredRole))) {
            throw new AccessDeniedException("Operation requires role " + requiredRole);
        }

        return auth.getName();
    }

    public String currentUser() {
        if (!enabled) {
            return "local-dev";
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required.");
        }

        return auth.getName();
    }

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
