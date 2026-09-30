package centuryroad.auth.security;

import centuryroad.auth.model.Role;
import centuryroad.auth.model.User;
import centuryroad.auth.service.JwtService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core promise this whole service exists to keep: a token JwtService signs
 * must be
 * exactly the one JwtVerifier accepts back, with the right claims - and nothing
 * signed
 * with a different key, or expired, should ever verify.
 */
class JwtRoundTripUnitTest {

    private static final String SECRET = "Y2VudHVyeS1yb2FkLXRlc3Qtc2VjcmV0LWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmctZm9yLWhzMjU2";

    private final JwtKey jwtKey = new JwtKey(SECRET);
    private final JwtService jwtService = new JwtService(jwtKey, 3_600_000L);
    private final JwtVerifier jwtVerifier = new JwtVerifier(jwtKey);

    private User user(long id, Role role) {
        User u = new User();
        u.setId(id);
        u.setEmail("mario.rossi@example.it");
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(OffsetDateTime.now());
        return u;
    }

    @Test
    void aTokenSignedByJwtServiceVerifiesBackToTheSameUser() {
        User user = user(7L, Role.USER);
        String token = jwtService.generateToken(user);

        AppPrincipal principal = jwtVerifier.verify(token);

        assertThat(principal).isNotNull();
        assertThat(principal.id()).isEqualTo(7L);
        assertThat(principal.email()).isEqualTo("mario.rossi@example.it");
        assertThat(principal.role()).isEqualTo(Role.USER);
    }

    @Test
    void theAdminRoleRoundTripsToo() {
        String token = jwtService.generateToken(user(1L, Role.ADMIN));

        AppPrincipal principal = jwtVerifier.verify(token);

        assertThat(principal).isNotNull();
        assertThat(principal.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void aTokenSignedWithADifferentKeyIsRejected() {
        JwtKey otherKey = new JwtKey("YW5vdGhlci1jb21wbGV0ZWx5LWRpZmZlcmVudC10ZXN0LXNlY3JldC1rZXk=");
        JwtService otherService = new JwtService(otherKey, 3_600_000L);

        String token = otherService.generateToken(user(7L, Role.USER));

        assertThat(jwtVerifier.verify(token)).isNull();
    }

    @Test
    void anExpiredTokenIsRejected() {
        String expired = Jwts.builder()
                .subject("mario.rossi@example.it")
                .claim("id", 7L)
                .claim("role", "USER")
                .issuedAt(new Date(System.currentTimeMillis() - 10_000))
                .expiration(new Date(System.currentTimeMillis() - 5_000))
                .signWith(jwtKey.get())
                .compact();

        assertThat(jwtVerifier.verify(expired)).isNull();
    }

    @Test
    void aTamperedTokenIsRejected() {
        String token = jwtService.generateToken(user(7L, Role.USER));
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThat(jwtVerifier.verify(tampered)).isNull();
    }

    @Test
    void garbageInputNeverThrows() {
        assertThat(jwtVerifier.verify("not-a-jwt-at-all")).isNull();
        assertThat(jwtVerifier.verify("")).isNull();
    }
}
