package centuryroad.auth.security;

import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;

/**
 * Builds the one SecretKey every signing and verification operation uses, from
 * jwt.secret. Accepts either base64 alphabet, standard ('+' and '/') or
 * url-safe
 * ('-' and '_'): "openssl rand -base64 48" produces standard base64, and a
 * secret
 * containing '/' decoded as url-safe has already broken a sibling project once.
 */
@Component
public class JwtKey {

    private final SecretKey key;

    public JwtKey(@Value("${jwt.secret}") String secret) {
        String normalized = secret.replace('-', '+').replace('_', '/');
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(normalized));
    }

    public SecretKey get() {
        return key;
    }
}
