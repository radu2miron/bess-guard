package edu.tucn.bessguard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Bearer JWT filter retained from the original application, extended with backend-owned roles.
 */
@Component
public class JwtAuthorizationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserAccountService accountService;
    private final boolean authorizationEnabled;

    public JwtAuthorizationFilter(JwtUtil jwtUtil, UserAccountService accountService,
                                  @Value("${authorization.enabled:true}") boolean authorizationEnabled) {
        this.jwtUtil = jwtUtil;
        this.accountService = accountService;
        this.authorizationEnabled = authorizationEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Bearer token required");
            return;
        }

        String jwtToken = authHeader.substring(7).trim();

        if (!jwtUtil.validateToken(jwtToken)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid bearer token");
            return;
        }

        String username = jwtUtil.getUsernameFromToken(jwtToken);
        String role = accountService.roleFor(username);

        if (role == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account is not configured");
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!authorizationEnabled) {
            return true;
        }

        String path = request.getRequestURI();
        return ("/login".equals(path) && "POST".equals(request.getMethod()))
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs")
                || "/".equals(path);
    }
}
