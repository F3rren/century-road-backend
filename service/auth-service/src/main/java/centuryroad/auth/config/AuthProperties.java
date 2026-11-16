package centuryroad.auth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every setting of this service, in one place, each under the property name it always had. Values and
 * defaults are in application.yml, not here; a value that is missing or out of range stops the
 * service at startup with the property named, instead of surfacing as a strange login failure.
 * HistoryProperties does the same in history-service.
 *
 * The records that carry a secret print it masked: a record's own toString would print every component,
 * and a configuration object is the kind of thing that ends up in a log or a failure report.
 */
public final class AuthProperties {

    private AuthProperties() {
    }

    /**
     * secret: base64 (either alphabet), at least 32 bytes once decoded - JwtKey enforces the length.
     * expirationMs: the access token's lifetime.
     */
    @Validated
    @ConfigurationProperties("jwt")
    public record Jwt(@NotBlank String secret, @Positive long expirationMs) {

        @Override
        public String toString() {
            return "Jwt[secret=<hidden>, expirationMs=" + expirationMs + "]";
        }
    }

    /** Login attempts allowed per ip|email within one window. */
    @Validated
    @ConfigurationProperties("auth.rate-limit")
    public record RateLimit(@Positive int maxAttempts, @Positive long windowMs) {
    }

    /** The first administrator, created only on an empty users table; both empty means "do nothing". */
    @Validated
    @ConfigurationProperties("bootstrap.admin")
    public record BootstrapAdmin(String email, String password) {

        public BootstrapAdmin {
            email = email == null ? "" : email;
            password = password == null ? "" : password;
        }

        @Override
        public String toString() {
            return "BootstrapAdmin[email=" + email + ", password=" + (password.isEmpty() ? "" : "<hidden>") + "]";
        }
    }

    /**
     * BCrypt's work factor for new hashes (4 to 31 is what BCrypt accepts). SecurityConfig adds the
     * policy on top: below 10 only under the test profile.
     */
    @Validated
    @ConfigurationProperties("security.password")
    public record PasswordHashing(@Min(4) @Max(31) int bcryptStrength) {
    }
}
