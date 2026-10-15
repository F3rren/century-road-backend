package centuryroad.history;

import centuryroad.history.model.TimelineEvent;
import centuryroad.history.repository.ErrorReportRepository;
import centuryroad.history.repository.TimelineEventRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The new endpoints in the whole service, on a real port and a real database: that the editorial
 * content is read and served by the real wiring (the fixed fixtures of editorial-test), that
 * "Sorprendimi" and "Nello stesso periodo" read what the index really holds, and that a report
 * really lands in the table V3 creates - and that the limit really stops the sixth.
 *
 * Reports are only sent from one test, so the rate limiter, which lives as long as the context,
 * is never shared with another.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EditorialAndReportsIntegrationTest {

    private static final OffsetDateTime NIGHT = OffsetDateTime.of(2026, 10, 2, 1, 0, 0, 0, ZoneOffset.UTC);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TimelineEventRepository timeline;

    @Autowired
    private ErrorReportRepository reports;

    @BeforeEach
    @AfterEach
    void clean() {
        timeline.deleteAll();
        reports.deleteAll();
    }

    private String get(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    // Not TestRestTemplate: its Apache client retries a 429 on its own, and waits for as long as
    // Retry-After says - an hour, here - which is the very answer this test is looking for.
    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theStartHerePathsAndInsightsAreServedFromTheContentTheServiceLoaded() {
        assertThat(JsonPath.<String>read(get("/api/history/start-here"), "$.data[0].slug")).isEqualTo("test-path");

        String path = get("/api/history/paths/test-path");
        assertThat(JsonPath.<List<?>>read(path, "$.data.stops")).hasSize(6);
        assertThat(JsonPath.<String>read(path, "$.data.stops[0].slug")).isEqualTo("test-a");

        String insight = get("/api/history/insights/test-a");
        assertThat(JsonPath.<String>read(insight, "$.data.before")).isEqualTo("Prima uno due tre.");
        assertThat(JsonPath.<String>read(insight, "$.data.inPaths[0].path")).isEqualTo("test-path");

        assertThat(JsonPath.<List<?>>read(get("/api/history/insights?month=3&day=5"), "$.data")).hasSize(3);
    }

    @Test
    void anUnknownPathOrInsightIsA404WithItsOwnCode() {
        ResponseEntity<String> response = rest.getForEntity("/api/history/paths/nowhere", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error")).isEqualTo("PATH_NOT_FOUND");
    }

    @Test
    void aRandomEventAndTheSamePeriodComeFromTheIndex() {
        timeline.saveAll(List.of(
                new TimelineEvent(null, "it", (short) 7, (short) 20, 1969, "US", "Allunaggio.", NIGHT),
                new TimelineEvent(null, "it", (short) 5, (short) 30, 1968, "FR", "Maggio francese.", NIGHT),
                new TimelineEvent(null, "it", (short) 10, (short) 10, 1964, "JP", "Olimpiadi di Tokyo.", NIGHT)));

        String random = get("/api/history/random?country=US");
        assertThat(JsonPath.<String>read(random, "$.data.event.text")).isEqualTo("Allunaggio.");

        String samePeriod = get("/api/history/same-period?year=1969&excludeCountry=US");
        assertThat(JsonPath.<List<?>>read(samePeriod, "$.data.countries")).hasSize(2);
        assertThat(JsonPath.<String>read(samePeriod, "$.data.comparison")).isEqualTo("TEMPORAL");
        assertThat(JsonPath.<String>read(samePeriod, "$.data.coverage.level")).isEqualTo("SPARSE");
    }

    @Test
    void withAnEmptyIndexTheAnswersAreHonestlyEmpty() {
        String random = get("/api/history/random");
        assertThat(JsonPath.<Object>read(random, "$.data.language")).isEqualTo("it");
        assertThat(random).doesNotContain("\"event\"");

        String samePeriod = get("/api/history/same-period?year=1969");
        assertThat(JsonPath.<String>read(samePeriod, "$.data.coverage.level")).isEqualTo("NONE");
    }

    @Test
    void theSourcesCountTheIndexAndTheEditorialContent() {
        timeline.save(new TimelineEvent(null, "it", (short) 7, (short) 20, 1969, "US", "Allunaggio.", NIGHT));

        String sources = get("/api/history/sources");

        assertThat(JsonPath.<Integer>read(sources, "$.data.coverage.index[0].eventCount")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(sources, "$.data.coverage.editorial.insights")).isEqualTo(6);
        assertThat(JsonPath.<Integer>read(sources, "$.data.coverage.editorial.reviewedInsights")).isEqualTo(1);
    }

    @Test
    void aReportLandsInTheTable_andTheSixthFromTheSameAddressIsRefused() throws Exception {
        String eventReport = """
                {"target":{"type":"EVENT","year":1957,"month":10,"day":4,"language":"it","text":"Viene lanciato lo Sputnik 1."},
                 "category":"WRONG_DATE","message":"A Baikonur era già il 5 ottobre.","contact":"nome@example.org"}""";

        HttpResponse<String> first = post("/api/history/reports", eventReport);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(first.body(), "$.data.id")).isPositive();

        var row = reports.findAll().get(0);
        assertThat(row.getTargetType()).isEqualTo("EVENT");
        assertThat(row.getTargetYear()).isEqualTo(1957);
        assertThat(row.getTargetText()).isEqualTo("Viene lanciato lo Sputnik 1.");
        assertThat(row.getContact()).isEqualTo("nome@example.org");
        assertThat(row.getHandledAt()).isNull();

        for (int i = 0; i < 4; i++) {
            assertThat(post("/api/history/reports", eventReport).statusCode()).isEqualTo(201);
        }
        HttpResponse<String> sixth = post("/api/history/reports", eventReport);

        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(sixth.headers().firstValue("Retry-After")).isPresent();
        assertThat(JsonPath.<String>read(sixth.body(), "$.error")).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(reports.count()).isEqualTo(5);
    }
}
