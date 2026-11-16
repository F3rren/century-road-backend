package centuryroad.auth.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * On the VPS the secrets are files (Docker secrets in /run/secrets, compose-prod.yml), each named after
 * the property it carries, and application.yml imports that directory as a config tree. This starts the
 * real configuration loading - application.yml included - against a temporary directory instead, with no
 * web server, no database and no beans of the service.
 */
class SecretFilesTest {

    private static final String SECRET = "Y2VudHVyeS1yb2FkLXRlc3Qtc2VjcmV0LWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmctZm9yLWhzMjU2";

    @Configuration(proxyBeanMethods = false)
    static class Nothing {
    }

    @AfterEach
    void forget() {
        System.clearProperty("CENTURY_ROAD_SECRETS_DIR");
        System.clearProperty("jwt.secret");
        System.clearProperty("JWT_SECRET");
    }

    private ConfigurableApplicationContext start(Path secrets) {
        System.setProperty("CENTURY_ROAD_SECRETS_DIR", secrets.toString());
        return new SpringApplicationBuilder(Nothing.class).web(WebApplicationType.NONE)
                .logStartupInfo(false).run();
    }

    @Test
    void aFileBecomesThePropertyItIsNamedAfterWithoutTheLineEndEchoLeaves(@TempDir Path secrets)
            throws IOException {
        // An environment variable would rightly win over the file (the next test): this one needs none.
        assumeTrue(System.getenv("JWT_SECRET") == null && System.getenv("SECURITY_PASSWORD_BCRYPT_STRENGTH") == null,
                "a variable in this environment would take precedence over the files");
        Files.writeString(secrets.resolve("jwt.secret"), SECRET + "\n");
        // A name application.yml also gives a value to (12): the file wins over it.
        Files.writeString(secrets.resolve("security.password.bcrypt-strength"), "13\n");

        try (ConfigurableApplicationContext context = start(secrets)) {
            assertThat(context.getEnvironment().getProperty("jwt.secret")).isEqualTo(SECRET);
            assertThat(context.getEnvironment().getProperty("security.password.bcrypt-strength")).isEqualTo("13");
        }
    }

    @Test
    void aValueSetOutsideTheFilesStillWins(@TempDir Path secrets) throws IOException {
        // A system property here, an environment variable on Railway: both rank above config files.
        Files.writeString(secrets.resolve("jwt.secret"), "from-the-file");
        System.setProperty("jwt.secret", SECRET);

        try (ConfigurableApplicationContext context = start(secrets)) {
            assertThat(context.getEnvironment().getProperty("jwt.secret")).isEqualTo(SECRET);
        }
    }

    @Test
    void withoutTheDirectoryTheVariableApplicationYmlNamesIsUsed(@TempDir Path somewhere) {
        // Railway, development: no /run/secrets, JWT_SECRET in the environment (a system property here).
        System.setProperty("JWT_SECRET", SECRET);

        try (ConfigurableApplicationContext context = start(somewhere.resolve("not-there"))) {
            assertThat(context.getEnvironment().getProperty("jwt.secret")).isEqualTo(SECRET);
        }
    }
}
