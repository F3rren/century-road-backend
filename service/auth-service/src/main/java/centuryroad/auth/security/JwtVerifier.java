package centuryroad.auth.security;

import centuryroad.auth.model.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

/**
 * Verifies a token's signature and expiry and turns its claims into an
 * AppPrincipal.
 * Every other Century Road service will eventually carry a copy of this class
 * (plus
 * JwtKey) to verify tokens offline, without ever calling this one back - the
 * same split
 * auth-service/everyone-else already uses in the classroom-backend project this
 * was
 * ported from: only this service depends on jjwt-impl, the signing half.
 */
@Component
public class JwtVerifier {

    private final JwtKey jwtKey;

    public JwtVerifier(JwtKey jwtKey) {
        this.jwtKey = jwtKey;
    }

    /**
     * Returns the verified principal, or null if the token is missing, expired,
     * malformed or signed with a different key - never throws for those cases,
     * since
     * "not authenticated" is a routine outcome here, not an exceptional one.
     */
    public AppPrincipal verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(jwtKey.get())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long id = claims.get("id", Long.class);
            String role = claims.get("role", String.class);
            return new AppPrincipal(id, claims.getSubject(), Role.valueOf(role));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
