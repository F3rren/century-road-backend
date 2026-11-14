package centuryroad.history;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON log format a deployment can switch on (LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs, Spring Boot's own,
 * no dependency): every line is one JSON object, and the request id is a field of it, so a log platform can
 * filter on it instead of searching text. Its own class, and it starts the logging system afresh and leaves
 * it as it found it: Spring Boot starts logging once per JVM, with the first context's configuration, so
 * without that this would see whatever format an earlier test class began with (see RequestIdInLogsTest).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.structured.format.console=ecs")
@ActiveProfiles("test")
@Import({NoRetryClientConfiguration.class, TestcontainersConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogsTest {

    private static final FakeWikipedia wikipedia = FakeWikipedia.start();

    static {
        wikipedia.reply("/it/api/rest_v1/feed/onthisday/all/11/11", FakeWikipedia.Reply.status(500));
        wikipedia.reply("/en/api/rest_v1/feed/onthisday/all/11/11", FakeWikipedia.Reply.status(500));
    }

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void startLoggingAfresh() {
        LoggingSystem.get(StructuredLogsTest.class.getClassLoader()).cleanUp();
    }

    @AfterAll
    static void stopFakeWikipediaAndLeaveLoggingAsFound() {
        wikipedia.close();
        LoggingSystem.get(StructuredLogsTest.class.getClassLoader()).cleanUp();
        // Boot hands the format to Logback as a system property and never clears it for a context that does not
        // ask for one: left set, every log line of every test class after this one would be JSON.
        System.clearProperty("CONSOLE_LOG_STRUCTURED_FORMAT");
    }

    @DynamicPropertySource
    static void pointAtTheFake(DynamicPropertyRegistry registry) {
        registry.add("history.wikipedia.host-template", () -> wikipedia.baseUrl() + "/{lang}");
    }

    @Test
    void withTheJsonFormatOn_theRequestIdIsAFieldOfTheLinesWrittenWhileAnswering(CapturedOutput output) throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/history/on-this-day/11/11?lang=it", String.class);
        String requestId = response.getHeaders().getFirst("X-Request-Id");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode line = null;
        for (String text : output.getOut().split("\n")) {
            if (text.startsWith("{") && text.contains("answered 500")) {
                line = mapper.readTree(text);
                break;
            }
        }

        assertThat(line).as("a JSON log line about Wikipedia answering 500").isNotNull();
        assertThat(line.path("requestId").asText()).isEqualTo(requestId);
        assertThat(line.path("message").asText()).contains("answered 500");
        assertThat(line.path("service").path("name").asText()).isEqualTo("history-service");
    }
}
