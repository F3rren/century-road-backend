package centuryroad.history;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens once Wikipedia says 429. Its own class, so its context - and with it the
 * cool-down, which would otherwise poison every other test - is not shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({NoRetryClientConfiguration.class, TestcontainersConfiguration.class})
class RateLimitIntegrationTest {

    private static final FakeWikipedia wikipedia = FakeWikipedia.start();

    static {
        Map<String, String> retryAfter = Map.of("Retry-After", "30");
        wikipedia.reply("/it/api/rest_v1/feed/onthisday/all/05/05", FakeWikipedia.Reply.status(429, retryAfter));
        wikipedia.reply("/en/api/rest_v1/feed/onthisday/all/05/05", FakeWikipedia.Reply.json("{}"));
    }

    @Autowired
    private TestRestTemplate rest;

    @AfterAll
    static void stopFakeWikipedia() {
        wikipedia.close();
    }

    @DynamicPropertySource
    static void pointAtTheFake(DynamicPropertyRegistry registry) {
        registry.add("history.wikipedia.host-template", () -> wikipedia.baseUrl() + "/{lang}");
    }

    @Test
    void aRateLimitBecomesA503WithRetryAfter_andWikipediaIsThenLeftAloneForThatLong() {
        ResponseEntity<String> first = rest.getForEntity("/api/history/on-this-day/5/5?lang=it", String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(first.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(first.getBody()).contains("UPSTREAM_RATE_LIMITED");
        // English is not tried either: the cool-down covers Wikipedia as a whole.
        assertThat(wikipedia.received()).hasSize(1);

        // A different day, right after: no request at all, but the same honest answer.
        ResponseEntity<String> second = rest.getForEntity("/api/history/on-this-day/6/6?lang=en", String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(Integer.parseInt(second.getHeaders().getFirst("Retry-After"))).isBetween(1, 30);
        assertThat(wikipedia.received()).hasSize(1);
    }
}
