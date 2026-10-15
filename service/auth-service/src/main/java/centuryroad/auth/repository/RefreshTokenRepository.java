package centuryroad.auth.repository;

import centuryroad.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Persistence for refresh tokens. findByTokenHash is the only lookup
 * RefreshTokenService
 * ever needs: the raw token is never stored, only its hash, so nobody with
 * database
 * access alone can read out a token good enough to use.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes the token if, and only if, it is still usable - in one statement. The row
     * count is the answer: 1 means this call is the one that used the token, 0 means it was
     * already revoked or has expired. Reading the token and then saving it revoked would let
     * two requests carrying the same token both pass the read.
     */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now"
            + " where t.id = :id and t.revokedAt is null and t.expiresAt > :now")
    int revokeIfActive(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
