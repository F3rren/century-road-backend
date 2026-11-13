package centuryroad.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The id minted for every request and handed back in X-Request-Id is only worth that if the service's own
 * log carries it: otherwise a person who quotes it has nothing to search for.
 *
 * Its own class: Spring Boot starts the logging system once per JVM, with the configuration of the first
 * context that is created, so a test about what the log looks like has to start it afresh (and leave it as
 * it found it), or it would see whatever format an earlier test class happened to start with.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.centuryroad.auth.service=WARN")
@Import({TestcontainersConfiguration.class, RestTemplateTestConfiguration.class})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RequestIdInLogsTest {

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void startLoggingAfresh() {
        LoggingSystem.get(RequestIdInLogsTest.class.getClassLoader()).cleanUp();
    }

    @AfterAll
    static void leaveLoggingAsFound() {
        LoggingSystem.get(RequestIdInLogsTest.class.getClassLoader()).cleanUp();
    }

    @Test
    void aLogLineWrittenWhileAnsweringCarriesTheRequestIdTheClientWasGiven(CapturedOutput output) {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/login",
                Map.of("email", "nobody@test.it", "password", "una-password-sbagliata"), String.class);

        String requestId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).matches("REQ_[0-9A-F]{8}");
        assertThat(output.getOut()).contains("Login refused").contains("[" + requestId + "]");
    }
}
