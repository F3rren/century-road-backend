package centuryroad.history.wikipedia;

import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.exception.UpstreamException;
import centuryroad.history.exception.UpstreamRateLimitedException;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Language;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.MonthDay;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Asks Wikipedia for a day and reports the outcome as a DayFeed or one of the three
 * UpstreamExceptions. This is the only class that knows what the URL looks like and what a
 * status code means; it applies no retry, cache or breaker of its own (see
 * ResilientWikipediaFeedClient), so it can be read and tested as a plain translation.
 *
 * Always asks for "all" rather than one section: the four lists of a day are wanted together
 * far more often than not, and one cached entry per day and language keeps the number of
 * requests Wikipedia ever sees at a few hundred, however many people use this.
 *
 * Month and day are zero-padded because the feed answers an unpadded "1/6" with a 404 page
 * in HTML, not JSON.
 */
@Slf4j
public class WikimediaFeedHttpClient implements WikipediaFeedClient {

    private static final String PATH = "/api/rest_v1/feed/onthisday/all/%02d/%02d";
    private static final String TIMER = "history.upstream.requests";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HistoryProperties.Wikipedia settings;
    private final MeterRegistry meters;
    private final Clock clock;

    public WikimediaFeedHttpClient(RestClient restClient, ObjectMapper objectMapper,
                                   HistoryProperties.Wikipedia settings, MeterRegistry meters, Clock clock) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public DayFeed fetchDay(Language language, MonthDay day) {
        URI uri = uriFor(language, day);
        Timer.Sample sample = Timer.start(meters);
        String outcome = "unavailable";
        try {
            DayFeed feed = restClient.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> read(response, language, day));
            outcome = "ok";
            return feed;
        } catch (UpstreamRateLimitedException e) {
            outcome = "rate_limited";
            throw e;
        } catch (UpstreamBadResponseException e) {
            outcome = "bad_response";
            throw e;
        } catch (UpstreamException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException("Could not reach Wikipedia (" + language.code() + ")", e);
        } catch (RestClientException e) {
            outcome = "bad_response";
            throw new UpstreamBadResponseException("Could not read Wikipedia's answer (" + language.code() + ")", e);
        } finally {
            sample.stop(Timer.builder(TIMER).tag("outcome", outcome).register(meters));
        }
    }

    private URI uriFor(Language language, MonthDay day) {
        String host = settings.hostTemplate().replace("{lang}", language.code());
        return URI.create(host + String.format(PATH, day.getMonthValue(), day.getDayOfMonth()));
    }

    private DayFeed read(ClientHttpResponse response, Language language, MonthDay day) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 200) {
            return parse(response, language, day);
        }
        if (status == 429) {
            Duration retryAfter = retryAfter(response.getHeaders());
            log.warn("Wikipedia ({}) rate-limited this service, staying away for {}s", language.code(),
                    retryAfter.toSeconds());
            throw new UpstreamRateLimitedException(retryAfter);
        }
        if (status >= 500 || status == 408) {
            log.warn("Wikipedia ({}) answered {}", language.code(), status);
            throw new UpstreamUnavailableException("Wikipedia answered " + status, null);
        }
        // The request was validated before it got here, so a 4xx means the API changed under us
        // - or that this client has been blocked (a 403), which is what a bad User-Agent earns.
        log.error("Wikipedia ({}) answered {} to a validated request - has the API changed, or is this client blocked?",
                language.code(), status);
        throw new UpstreamBadResponseException("Wikipedia answered " + status + " to a validated request");
    }

    private DayFeed parse(ClientHttpResponse response, Language language, MonthDay day) throws IOException {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            throw new UpstreamBadResponseException("Unexpected content type " + contentType);
        }
        byte[] body = response.getBody().readNBytes(settings.maxBodyBytes() + 1);
        if (body.length > settings.maxBodyBytes()) {
            throw new UpstreamBadResponseException("Response larger than " + settings.maxBodyBytes() + " bytes");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return WikimediaFeedParser.parse(root, language, day);
        } catch (JsonProcessingException e) {
            throw new UpstreamBadResponseException("Response is not valid JSON", e);
        }
    }

    /** Retry-After is either a number of seconds or an HTTP date. Missing, malformed or
     *  already past falls back to the default; anything absurd is capped, so a bad header
     *  cannot park the service for a day. */
    private Duration retryAfter(HttpHeaders headers) {
        Duration wait = settings.defaultCooldown();
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value != null) {
            wait = parseRetryAfter(value.trim()).orElse(wait);
        }
        if (wait.isNegative() || wait.isZero()) {
            wait = settings.defaultCooldown();
        }
        return wait.compareTo(settings.maxCooldown()) > 0 ? settings.maxCooldown() : wait;
    }

    private Optional<Duration> parseRetryAfter(String value) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (NumberFormatException notSeconds) {
            try {
                ZonedDateTime date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                return Optional.of(Duration.between(clock.instant(), date.toInstant()));
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
    }
}
