package centuryroad.auth.security;

import centuryroad.auth.config.ApiAccessDeniedHandler;
import centuryroad.auth.config.ApiAuthenticationEntryPoint;
import centuryroad.auth.config.JwtAuthFilter;
import centuryroad.auth.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private final SecurityConfig config = new SecurityConfig(mock(JwtAuthFilter.class),
            mock(ApiAuthenticationEntryPoint.class), mock(ApiAccessDeniedHandler.class), false);

    /** What the application builds when nothing is configured: the default, outside the test profile. */
    private final PasswordEncoder encoder = config.passwordEncoder(SecurityConfig.DEFAULT_BCRYPT_STRENGTH,
            new MockEnvironment());

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
    void theDefaultWorkFactorIsTwelve() {
        // Each step doubles what an attacker pays per guess (and what a login costs).
        int cost = Integer.parseInt(encoder.encode(PASSWORD).substring(4, 6));

        assertThat(SecurityConfig.DEFAULT_BCRYPT_STRENGTH).isEqualTo(12);
        assertThat(cost).isEqualTo(12);
    }

    @Test
    void aWeakerFactorIsRefusedUnlessTheTestProfileIsActive() {
        assertThatThrownBy(() -> config.passwordEncoder(4, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bcrypt-strength");

        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertThat(config.passwordEncoder(4, test).encode(PASSWORD)).startsWith("$2a$04$");
    }
}
