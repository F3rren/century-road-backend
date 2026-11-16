package centuryroad.auth.service;

import centuryroad.auth.config.AuthProperties;
import centuryroad.auth.model.User;
import centuryroad.auth.security.JwtKey;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Signs access tokens. The only service in this project that does: everyone
 * else
 * verifies with JwtVerifier and jjwt-api alone, never touching jjwt-impl.
 */
@Service
public class JwtService {

    private final JwtKey jwtKey;
    private final long expirationMs;

    public JwtService(JwtKey jwtKey, AuthProperties.Jwt jwt) {
        this.jwtKey = jwtKey;
        this.expirationMs = jwt.expirationMs();
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("id", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(jwtKey.get())
                .compact();
    }
}
