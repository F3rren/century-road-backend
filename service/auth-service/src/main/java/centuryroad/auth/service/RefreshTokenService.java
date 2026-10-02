package centuryroad.auth.service;

import centuryroad.auth.model.RefreshToken;
import centuryroad.auth.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues, rotates and revokes refresh tokens. The raw value is handed to the
 * caller
 * exactly once, at issuing time, and never again - only its SHA-256 hash is
 * stored,
 * exactly like User.password.
 */
@Service
public class RefreshTokenService {

    private static final Duration TTL = Duration.ofDays(30);
    private final SecureRandom random = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public String issue(Long userId) {
        String rawToken = randomToken();

        RefreshToken entity = new RefreshToken();
        entity.setUserId(userId);
        entity.setTokenHash(hash(rawToken));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setExpiresAt(OffsetDateTime.now().plus(TTL));
        refreshTokenRepository.save(entity);

        return rawToken;
    }

    /**
     * Verifies the raw token, revokes it, and returns the user id it belonged to -
     * or
     * empty if it is unknown, expired or already revoked. Single-use by design: a
     * refresh call always rotates, so a stolen-and-replayed old token stops working
     * the
     * moment the legitimate client refreshes once.
     */
    public Optional<Long> rotate(String rawToken) {
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        RefreshToken entity = found.get();
        if (entity.getRevokedAt() != null || entity.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return Optional.empty();
        }

        entity.setRevokedAt(OffsetDateTime.now());
        refreshTokenRepository.save(entity);
        return Optional.of(entity.getUserId());
    }

    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(entity -> {
            entity.setRevokedAt(OffsetDateTime.now());
            refreshTokenRepository.save(entity);
        });
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
