package centuryroad.history;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole service on a real port, talking real HTTP to a stand-in for Wikipedia. What no
 * unit test can show: that the wiring holds together, that gzip really is requested and
 * really is undone, and what actually goes over the wire to Wikipedia.
 *
 * The cache lives as long as the context, so a test that needs Wikipedia to be asked afresh
 * uses a day of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({NoRetryClientConfiguration.class, TestcontainersConfiguration.class})
class HistoryServiceIntegrationTest {

    private static final String IT_10_16 = "/it/api/rest_v1/feed/onthisday/all/10/16";
    private static final String EN_10_16 = "/en/api/rest_v1/feed/onthisday/all/10/16";
    private static final String EXPECTED_USER_AGENT =
            "CenturyRoad-History/0.1 (https://example.invalid/century-road-tests) spring-restclient";

    // Started as a static initialiser, not @BeforeAll: it must be listening before Spring
    // resolves the @DynamicPropertySource value while it builds the context.
    private static final FakeWikipedia wikipedia = FakeWikipedia.start();

    static {
        wikipedia.reply(IT_10_16, FakeWikipedia.Reply.json(Fixtures.text("it-10-16.json")));
        wikipedia.reply(EN_10_16, FakeWikipedia.Reply.json(Fixtures.text("en-10-16.json")));
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

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }

    private static <T> T read(ResponseEntity<String> response, String jsonPath) {
        return JsonPath.read(response.getBody(), jsonPath);
    }

    // ----- the whole way through --------------------------------------------------------

    @Test
    void aDayIsFetchedCleanedAndServed_withGapsFilledFromTheOtherLanguage() {
        ResponseEntity<String> response = get("/api/history/on-this-day/10/16?lang=it");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) read(response, "$.success")).isTrue();
        assertThat((List<?>) read(response, "$.data.sections.events.items")).hasSize(3);
        assertThat((String) read(response, "$.data.sections.events.language")).isEqualTo("it");
        assertThat((Boolean) read(response, "$.data.sections.events.fallback")).isFalse();
        // Italian has no births: English fills them in, and says so.
        assertThat((String) read(response, "$.data.sections.births.language")).isEqualTo("en");
        assertThat((Boolean) read(response, "$.data.sections.births.fallback")).isTrue();
        assertThat((List<?>) read(response, "$.data.sections.births.items")).hasSize(2);
    }

    @Test
    void whatIsSentToWikipediaIdentifiesUsAndAsksForGzip() {
        get("/api/history/on-this-day/10/16?lang=it");

        FakeWikipedia.Received request = wikipedia.received().stream()
                .filter(r -> r.path().equals(IT_10_16)).findFirst().orElseThrow();
        assertThat(request.userAgent()).isEqualTo(EXPECTED_USER_AGENT);
        assertThat(request.acceptEncoding()).contains("gzip");
        assertThat(request.accept()).contains("application/json");
    }

    @Test
    void theResponseWasGzippedOnTheWireAndStillParses() {
        // The fake compresses whenever the client says it accepts it. That the answer below
        // is intact is the proof that the client really decompressed it.
        ResponseEntity<String> response = get("/api/history/on-this-day/10/16?lang=it&types=selected");

        assertThat((List<?>) read(response, "$.data.sections.selected.items")).hasSize(1);
        assertThat((String) read(response, "$.data.sections.selected.items[0].text"))
                .isEqualTo("Un cardinale polacco viene eletto papa.");
    }

    @Test
    void aCommonsImageComesWithItsFilePage_aLocalOneIsLeftOut_andNoHtmlIsForwarded() {
        ResponseEntity<String> response = get("/api/history/on-this-day/10/16?lang=it&types=selected");

        assertThat((String) read(response, "$.data.sections.selected.items[0].pages[0].thumbnail.filePageUrl"))
                .isEqualTo("https://commons.wikimedia.org/wiki/File:Papa_Esempio.jpg");
        Map<String, Object> localImagePage = read(response, "$.data.sections.selected.items[0].pages[1]");
        assertThat(localImagePage).containsEntry("title", "Conclave di esempio").doesNotContainKey("thumbnail");
        assertThat(response.getBody()).doesNotContain("<span").doesNotContain("extract_html");
    }

    @Test
    void theSecondRequestForTheSameDayNeverReachesWikipedia() {
        get("/api/history/on-this-day/10/16?lang=it");
        long italianBefore = wikipedia.requestsTo(IT_10_16);
        long englishBefore = wikipedia.requestsTo(EN_10_16);

        ResponseEntity<String> again = get("/api/history/on-this-day/10/16?lang=it");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wikipedia.requestsTo(IT_10_16)).isEqualTo(italianBefore);
        assertThat(wikipedia.requestsTo(EN_10_16)).isEqualTo(englishBefore);
    }

    @Test
    void aYearFilterIsAppliedToWhatWikipediaSent() {
        ResponseEntity<String> response = get("/api/history/on-this-day/10/16?lang=it&types=events&year=-44");

        assertThat((List<?>) read(response, "$.data.sections.events.items")).hasSize(1);
        assertThat((String) read(response, "$.data.sections.events.items[0].text"))
                .isEqualTo("Un evento di prima dell'era volgare.");
    }

    // ----- failures ---------------------------------------------------------------------

    @Test
    void whenWikipediaFailsForBothLanguagesTheAnswerIsA503InTheEnvelope_revealingNothing() {
        wikipedia.reply("/it/api/rest_v1/feed/onthisday/all/03/03", FakeWikipedia.Reply.status(500));
        wikipedia.reply("/en/api/rest_v1/feed/onthisday/all/03/03", FakeWikipedia.Reply.status(500));

        ResponseEntity<String> response = get("/api/history/on-this-day/3/3?lang=it");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat((String) read(response, "$.error")).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(response.getBody()).doesNotContain("127.0.0.1").doesNotContain("/api/rest_v1");
    }

    @Test
    void aLanguageThatKeepsFailingIsTriedTwiceThenTheFallbackAnswers() {
        String italian = "/it/api/rest_v1/feed/onthisday/all/04/04";
        wikipedia.reply(italian, FakeWikipedia.Reply.status(503));
        wikipedia.reply("/en/api/rest_v1/feed/onthisday/all/04/04",
                FakeWikipedia.Reply.json(Fixtures.text("en-10-16.json")));

        ResponseEntity<String> response = get("/api/history/on-this-day/4/4?lang=it");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) read(response, "$.data.warnings")).containsExactly("PRIMARY_UNAVAILABLE");
        assertThat((String) read(response, "$.data.sections.events.language")).isEqualTo("en");
        assertThat(wikipedia.requestsTo(italian)).as("one try and one retry").isEqualTo(2);
    }

    @Test
    void aRedirectIsNeverFollowed_soAnAnswerCannotSendTheServiceSomewhereElse() {
        // The Location points at a port nothing listens on; if it were followed the request
        // would fail as unavailable, not as a bad response, and the counts below would differ.
        Map<String, String> redirect = Map.of("Location", "http://127.0.0.1:1/somewhere-else");
        wikipedia.reply("/it/api/rest_v1/feed/onthisday/all/07/07", FakeWikipedia.Reply.status(301, redirect));
        wikipedia.reply("/en/api/rest_v1/feed/onthisday/all/07/07", FakeWikipedia.Reply.status(301, redirect));

        ResponseEntity<String> response = get("/api/history/on-this-day/7/7?lang=it");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat((String) read(response, "$.error")).isEqualTo("UPSTREAM_BAD_RESPONSE");
        assertThat(wikipedia.requestsTo("/it/api/rest_v1/feed/onthisday/all/07/07")).isEqualTo(1);
    }

    @Test
    void invalidInputNeverReachesWikipedia() {
        int before = wikipedia.received().size();

        assertThat(get("/api/history/on-this-day/2/30").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/history/on-this-day/10/16?lang=../../evil").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/history/on-this-day/10/16?types=wars").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(wikipedia.received()).hasSize(before);
    }

    // ----- what a client and Prometheus see ---------------------------------------------

    @Test
    void theAnswerCarriesCachingAndCorrelationHeaders() {
        ResponseEntity<String> response = get("/api/history/on-this-day/10/16?lang=it");

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=300, public");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).matches("REQ_[0-9A-F]{8}");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith("application/json");
    }

    @Test
    void healthAndPrometheusAreExposed_theMetricsAndInfoEndpointsAreNot() {
        get("/api/history/on-this-day/10/16?lang=it");

        assertThat(get("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> prometheus = get("/actuator/prometheus");
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody())
                .contains("history_upstream_requests_seconds_count")
                .contains("outcome=\"ok\"")
                .contains("history_feed");
        assertThat(get("/actuator/metrics").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/actuator/info").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
