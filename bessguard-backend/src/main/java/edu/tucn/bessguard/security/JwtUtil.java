package edu.tucn.bessguard.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.function.Function;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Bearer-token authentication pattern (HS512, username subject, 24-hour validity).
 * A configured signing key survives application restarts. Without one, a temporary key is generated.
 */
@Component
public class JwtUtil {

    public static final long JWT_TOKEN_VALIDITY = 24 * 60 * 60 * 1000L;

    private final SecretKey secretKey;

    public JwtUtil(@Value("${jwt.secret-base64:}") String configuredKey) {
        byte[] bytes;

        if (configuredKey == null || configuredKey.isBlank()) {
            bytes = new byte[64];
            new SecureRandom().nextBytes(bytes);
        } else {
            bytes = Base64.getDecoder().decode(configuredKey);

            if (bytes.length < 64) {
                throw new IllegalArgumentException("jwt.secret-base64 must decode to at least 64 bytes for HS512.");
            }
        }

        this.secretKey = Keys.hmacShaKeyFor(bytes);
    }

    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public Date getExpirationDateFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(getAllClaimsFromToken(token));
    }

    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }

    public String generateToken(String username) {
        Date now = new Date();

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + JWT_TOKEN_VALIDITY))
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            String username = getUsernameFromToken(token);
            Date expiration = getExpirationDateFromToken(token);
            return username != null && !username.isBlank() && expiration != null && expiration.after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
