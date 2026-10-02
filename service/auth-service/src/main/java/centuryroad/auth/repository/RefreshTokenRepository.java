package centuryroad.auth.repository;

import centuryroad.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
