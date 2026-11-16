package centuryroad.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings are checked at startup, not at the first login: each of these values stops the
 * application context with the offending property named. Only the binding is started here, no web
 * server and no database.
 */
class AuthPropertiesTest {

    private static final String SECRET = "Y2VudHVyeS1yb2FkLXRlc3Qtc2VjcmV0LWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmctZm9yLWhzMjU2";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            AuthProperties.Jwt.class,
            AuthProperties.RateLimit.class,
            AuthProperties.BootstrapAdmin.class,
            AuthProperties.PasswordHashing.class })
    static class Binding {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Binding.class)
            .withPropertyValues(
                    "jwt.secret=" + SECRET,
                    "jwt.expiration-ms=3600000",
                    "auth.rate-limit.max-attempts=5",
                    "auth.rate-limit.window-ms=60000",
                    "security.password.bcrypt-strength=12");

    @Test
    void validSettingsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuthProperties.Jwt.class).expirationMs()).isEqualTo(3_600_000L);
            assertThat(context.getBean(AuthProperties.RateLimit.class).maxAttempts()).isEqualTo(5);
            assertThat(context.getBean(AuthProperties.PasswordHashing.class).bcryptStrength()).isEqualTo(12);
            // Absent bootstrap values mean "do nothing", never null.
            assertThat(context.getBean(AuthProperties.BootstrapAdmin.class).email()).isEmpty();
        });
    }

    @Test
    void aBlankSecretStopsTheStart() {
        runner.withPropertyValues("jwt.secret= ").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("jwt").hasMessageContaining("secret"));
    }

    @Test
    void aTokenLifetimeOfZeroStopsTheStart() {
        runner.withPropertyValues("jwt.expiration-ms=0").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("expirationMs"));
    }

    @Test
    void aRateLimitOfZeroStopsTheStart() {
        runner.withPropertyValues("auth.rate-limit.max-attempts=0").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("maxAttempts"));
    }

    @Test
    void aWorkFactorBCryptDoesNotAcceptStopsTheStart() {
        runner.withPropertyValues("security.password.bcrypt-strength=3").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("bcryptStrength"));
    }

    @Test
    void theSecretsNeverReachToString() {
        assertThat(new AuthProperties.Jwt(SECRET, 1L).toString()).doesNotContain(SECRET).contains("hidden");
        assertThat(new AuthProperties.BootstrapAdmin("a@b.c", "a-password").toString())
                .doesNotContain("a-password").contains("a@b.c");
    }
}
