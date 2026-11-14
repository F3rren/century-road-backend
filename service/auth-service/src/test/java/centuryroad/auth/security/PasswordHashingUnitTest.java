package centuryroad.auth.security;

import centuryroad.auth.config.ApiAccessDeniedHandler;
import centuryroad.auth.config.ApiAuthenticationEntryPoint;
import centuryroad.auth.config.JwtAuthFilter;
import centuryroad.auth.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What stands between a stolen users table and the passwords in it. The encoder is Spring's BCrypt,
 * which draws a fresh random salt for every hash and writes it inside the hash: two accounts with
 * the same password never share a hash, and cracking one says nothing about the other. This pins
 * that on the encoder the application really builds, so that swapping it for an unsalted or fast
 * hash (a plain SHA-256, say) fails a test instead of passing review unnoticed.
 */
class PasswordHashingUnitTest {

    private static final String PASSWORD = "correct horse battery staple";

    private final PasswordEncoder encoder = new SecurityConfig(mock(JwtAuthFilter.class),
            mock(ApiAuthenticationEntryPoint.class), mock(ApiAccessDeniedHandler.class), false).passwordEncoder();

    @Test
    void theSamePasswordHashedTwiceGivesTwoDifferentHashesThatBothMatch() {
        String first = encoder.encode(PASSWORD);
        String second = encoder.encode(PASSWORD);

        assertThat(first).isNotEqualTo(second);
        assertThat(encoder.matches(PASSWORD, first)).isTrue();
        assertThat(encoder.matches(PASSWORD, second)).isTrue();
        assertThat(encoder.matches("another password", first)).isFalse();
    }

    @Test
    void theSaltIsInTheHashAndDiffersEachTime() {
        // "$2a$10$" followed by 22 characters of salt, then the 31 of the hash itself.
        String first = encoder.encode(PASSWORD);
        String second = encoder.encode(PASSWORD);

        assertThat(first).matches("^\\$2[aby]\\$\\d{2}\\$.{53}$");
        assertThat(first.substring(0, 29)).isNotEqualTo(second.substring(0, 29));
    }

    @Test
    void theWorkFactorIsAtLeastTen() {
        // OWASP's floor for bcrypt: each step doubles what an attacker pays per guess.
        int cost = Integer.parseInt(encoder.encode(PASSWORD).substring(4, 6));

        assertThat(cost).isGreaterThanOrEqualTo(10);
    }
}
