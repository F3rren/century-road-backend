package centuryroad.history.wikipedia;

import centuryroad.history.Fixtures;
import centuryroad.history.TestClock;
import centuryroad.history.TestSupport;
import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.exception.UpstreamRateLimitedException;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Language;
import centuryroad.history.model.Section;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The translation from what Wikipedia can do to the three things this service understands.
 * No network: a mock server stands behind the RestClient, so every status, header and failure
 * the live API is known to produce - and the ones it may produce - can be replayed.
 */
class WikimediaFeedHttpClientUnitTest {

    private static final String USER_AGENT = "Test/1 (https://example.invalid/contact) spring-restclient";
    private static final MonthDay DAY = MonthDay.of(10, 16);
    private static final String IT_URL = "http://wikipedia.invalid/it/api/rest_v1/feed/onthisday/all/10/16";

    private final TestClock clock = new TestClock(Instant.parse("2026-10-21T07:27:30Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MockRestServiceServer server;
    private WikimediaFeedHttpClient client;

    @BeforeEach
    void setUp() {
        client = clientWith(TestSupport.wikipediaSettings());
    }

    private WikimediaFeedHttpClient clientWith(HistoryProperties.Wikipedia settings) {
        RestClient.Builder builder = RestClient.builder().defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT);
        server = MockRestServiceServer.bindTo(builder).build();
        return new WikimediaFeedHttpClient(builder.build(), new ObjectMapper(), settings, meters, clock);
    }

    private static HistoryProperties.Wikipedia settingsWithMaxBody(int maxBodyBytes) {
        HistoryProperties.Wikipedia base = TestSupport.wikipediaSettings();
        return new HistoryProperties.Wikipedia(base.hostTemplate(), base.clientName(), base.contact(),
                base.connectTimeout(), base.readTimeout(), maxBodyBytes, base.defaultCooldown(), base.maxCooldown());
    }

    private long count(String outcome) {
        return meters.get("history.upstream.requests").tag("outcome", outcome).timer().count();
    }

    /** The wait a 429 carrying this Retry-After (or none) is turned into. */
    private Duration retryAfterFor(String header) {
        server.expect(requestTo(IT_URL)).andRespond(header == null
                ? withStatus(HttpStatus.TOO_MANY_REQUESTS)
                : withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, header));
        try {
            client.fetchDay(Language.IT, DAY);
            throw new AssertionError("expected a 429 to be reported");
        } catch (UpstreamRateLimitedException e) {
            return e.getRetryAfter();
        }
    }

    // ----- success ----------------------------------------------------------------------

    @Test
    void aGoodAnswerBecomesADayFeed() {
        server.expect(requestTo(IT_URL)).andRespond(withSuccess(Fixtures.text("it-10-16.json"), MediaType.APPLICATION_JSON));

        DayFeed feed = client.fetchDay(Language.IT, DAY);

        assertThat(feed.language()).isEqualTo(Language.IT);
        assertThat(feed.day()).isEqualTo(DAY);
        assertThat(feed.entries(Section.EVENTS)).hasSize(3);
        assertThat(count("ok")).isEqualTo(1);
    }

    @Test
    void theRequestNamesTheClientAndPadsTheDateWithZeros() {
        // "1/6" without padding is a 404 in HTML on the live API.
        server.expect(requestTo("http://wikipedia.invalid/en/api/rest_v1/feed/onthisday/all/01/06"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.USER_AGENT, USER_AGENT))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.fetchDay(Language.EN, MonthDay.of(1, 6));

        server.verify();
    }

    @Test
    void theJsonContentTypeWithWikipediasProfileParameterIsStillJson() {
        String contentType = "application/json; charset=utf-8; profile=\"https://www.mediawiki.org/wiki/Specs/onthisday-feed/0.5.0\"";
        server.expect(requestTo(IT_URL)).andRespond(withSuccess("{\"events\":[]}", MediaType.parseMediaType(contentType)));

        assertThat(client.fetchDay(Language.IT, DAY).entries(Section.EVENTS)).isEmpty();
    }

    // ----- 429 --------------------------------------------------------------------------

    @Test
    void aRateLimitIsReportedWithTheWaitWikipediaAskedFor() {
        assertThat(retryAfterFor("7")).isEqualTo(Duration.ofSeconds(7));
        assertThat(count("rate_limited")).isEqualTo(1);
    }

    @Test
    void aRetryAfterGivenAsADateIsMeasuredFromNow() {
        // The clock reads 07:27:30; the header says 07:28:00.
        assertThat(retryAfterFor("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aMissingMalformedOrPastRetryAfterFallsBackToTheDefault() {
        assertThat(retryAfterFor(null)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void anAbsurdWaitIsCappedSoABadHeaderCannotParkTheServiceForADay() {
        assertThat(retryAfterFor("86400")).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void zeroIsTreatedAsMissing_becauseZeroWouldInviteAnImmediateRetry() {
        assertThat(retryAfterFor("0")).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void garbageInRetryAfterIsTreatedAsMissing() {
        assertThat(retryAfterFor("soon")).isEqualTo(Duration.ofSeconds(5));
    }

    // ----- unavailable ------------------------------------------------------------------

    @Test
    void aServerErrorIsUnavailable() {
        server.expect(requestTo(IT_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY)).isInstanceOf(UpstreamUnavailableException.class);
        assertThat(count("unavailable")).isEqualTo(1);
    }

    @Test
    void aRequestTimeoutStatusIsUnavailableToo() {
        server.expect(requestTo(IT_URL)).andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));

        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY)).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void aTimeoutOrAConnectionFailureIsUnavailable_withTheCauseKept() {
        for (IOException failure : new IOException[]{new SocketTimeoutException("read timed out"),
                new ConnectException("refused")}) {
            server.reset();
            server.expect(requestTo(IT_URL)).andRespond(withException(failure));

            assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY))
                    .isInstanceOf(UpstreamUnavailableException.class)
                    .hasRootCauseInstanceOf(failure.getClass());
        }
    }

    // ----- bad response -----------------------------------------------------------------

    @Test
    void aNotFoundIsABadResponse_notAnEmptyDay_becauseTheRequestWasValidatedFirst() {
        // The live API answers a malformed path with an HTML 404.
        server.expect(requestTo(IT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML).body("<html><pre>Cannot GET /x</pre></html>"));

        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY)).isInstanceOf(UpstreamBadResponseException.class);
        assertThat(count("bad_response")).isEqualTo(1);
    }

    @Test
    void aForbiddenIsABadResponseToo_whichIsWhatABlockedClientLooksLike() {
        server.expect(requestTo(IT_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY)).isInstanceOf(UpstreamBadResponseException.class);
    }

    @Test
    void a200ThatIsNotJsonIsRefused() {
        server.expect(requestTo(IT_URL)).andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY))
                .isInstanceOf(UpstreamBadResponseException.class)
                .hasMessageContaining("content type");
    }

    @Test
    void jsonThatDoesNotParseIsRefused() {
        server.expect(requestTo(IT_URL)).andRespond(withSuccess("{\"events\": [", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY))
                .isInstanceOf(UpstreamBadResponseException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void jsonThatIsNotAnObjectIsRefused() {
        server.expect(requestTo(IT_URL)).andRespond(withSuccess("[1,2,3]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY)).isInstanceOf(UpstreamBadResponseException.class);
    }

    @Test
    void aBodyOverTheLimitIsRefusedRatherThanReadWhole() {
        WikimediaFeedHttpClient tiny = clientWith(settingsWithMaxBody(100));
        server.expect(requestTo(IT_URL)).andRespond(withSuccess(Fixtures.text("it-10-16.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> tiny.fetchDay(Language.IT, DAY))
                .isInstanceOf(UpstreamBadResponseException.class)
                .hasMessageContaining("larger than 100 bytes");
    }

    @Test
    void aBodyExactlyAtTheLimitIsAccepted() {
        String body = "{\"events\":[]}";
        WikimediaFeedHttpClient exact = clientWith(settingsWithMaxBody(body.length()));
        server.expect(requestTo(IT_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(exact.fetchDay(Language.IT, DAY).entries(Section.EVENTS)).isEmpty();
    }
}
