package centuryroad.history;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The id minted for every request and handed back in X-Request-Id is only worth that if the service's own
 * log carries it: otherwise a person who quotes it has nothing to search for.
 *
 * Its own class, for two reasons. Its failing Wikipedia would trip the circuit breaker of a context shared
 * with other tests. And Spring Boot starts the logging system once per JVM, with the configuration of the
 * first context that is created, so a test about what the log looks like has to start it afresh (and leave
 * it as it found it), or it would see whatever format an earlier test class happened to start with.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.centuryroad.history.wikipedia=WARN")
@ActiveProfiles("test")
@Import({NoRetryClientConfiguration.class, TestcontainersConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class RequestIdInLogsTest {

    private static final FakeWikipedia wikipedia = FakeWikipedia.start();

    static {
        wikipedia.reply("/it/api/rest_v1/feed/onthisday/all/12/12", FakeWikipedia.Reply.status(500));
        wikipedia.reply("/en/api/rest_v1/feed/onthisday/all/12/12", FakeWikipedia.Reply.status(500));
    }

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void startLoggingAfresh() {
        LoggingSystem.get(RequestIdInLogsTest.class.getClassLoader()).cleanUp();
    }

    @AfterAll
    static void leaveLoggingAsFound() {
        wikipedia.close();
        LoggingSystem.get(RequestIdInLogsTest.class.getClassLoader()).cleanUp();
    }

    @DynamicPropertySource
    static void pointAtTheFake(DynamicPropertyRegistry registry) {
        registry.add("history.wikipedia.host-template", () -> wikipedia.baseUrl() + "/{lang}");
    }

    @Test
    void aLogLineWrittenWhileAnsweringCarriesTheRequestIdTheClientWasGiven(CapturedOutput output) {
        ResponseEntity<String> response = rest.getForEntity("/api/history/on-this-day/12/12?lang=it", String.class);

        String requestId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).matches("REQ_[0-9A-F]{8}");
        assertThat(output.getOut()).contains("Wikipedia (it) answered 500").contains("[" + requestId + "]");
    }
}
