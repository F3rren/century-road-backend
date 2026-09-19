package centuryroad.history.config;

import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.exception.UpstreamRateLimitedException;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.wikipedia.ResilientWikipediaFeedClient;
import centuryroad.history.wikipedia.UpstreamCooldown;
import centuryroad.history.wikipedia.WikimediaFeedHttpClient;
import centuryroad.history.wikipedia.WikipediaFeedClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Assembles everything between the service and Wikipedia. The resilience decorators are
 * built by hand, not through annotations, so the order they wrap in - see
 * ResilientWikipediaFeedClient - is written down in code rather than implied by proxy
 * ordering.
 */
@Configuration
public class WikimediaClientConfig {

    private static final String NAME = "wikipedia";
    private static final int BREAKER_WINDOW = 20;
    private static final int BREAKER_MIN_CALLS = 10;
    private static final float BREAKER_FAILURE_RATE = 50f;
    private static final int BREAKER_HALF_OPEN_CALLS = 3;
    /** Wikipedia asks for three or fewer at once; a little headroom for the two hosts. */
    private static final int MAX_CONNECTIONS = 6;

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The HTTP stack, built by hand for one reason above all: Apache HttpClient retries a
     * 429 or 503 on its own by default, and honours Retry-After while doing it. That would
     * put a request thread to sleep for as long as Wikipedia asked and send a second request
     * the moment it woke - exactly what the cool-down exists to prevent, and invisible from
     * the code above. So automatic retries are off; ResilientWikipediaFeedClient decides what
     * gets retried. Gzip stays on: the client asks for it and undoes it without being told.
     *
     * Redirects are off too. The URL is fixed and validated here, so a redirect is outside
     * the contract, and following one would let a misbehaving response send this service to a
     * host of its choosing - from inside the Compose network, where that host could be the
     * database. A 3xx surfaces as an unusable answer instead, which is also how a moved API
     * gets noticed.
     *
     * A bean of its own so Spring closes the connection pool on shutdown.
     */
    @Bean
    public HttpComponentsClientHttpRequestFactory wikimediaRequestFactory(HistoryProperties properties) {
        Timeout connect = Timeout.of(properties.wikipedia().connectTimeout());
        Timeout read = Timeout.of(properties.wikipedia().readTimeout());
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnPerRoute(MAX_CONNECTIONS)
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connect).setSocketTimeout(read).build())
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(connect).setResponseTimeout(read).build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
        return new HttpComponentsClientHttpRequestFactory(client);
    }

    /** Identifies this service to Wikimedia on every request, as its User-Agent policy requires. */
    @Bean
    public RestClient wikimediaRestClient(RestClient.Builder builder, HistoryProperties properties,
                                          HttpComponentsClientHttpRequestFactory wikimediaRequestFactory) {
        return builder
                .requestFactory(wikimediaRequestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.wikipedia().userAgent())
                .build();
    }

    @Bean
    public UpstreamCooldown upstreamCooldown(Clock clock) {
        return new UpstreamCooldown(clock);
    }

    @Bean
    public CircuitBreaker wikipediaCircuitBreaker(HistoryProperties properties, MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry.circuitBreaker(NAME, CircuitBreakerConfig.custom()
                .slidingWindowSize(BREAKER_WINDOW)
                .minimumNumberOfCalls(BREAKER_MIN_CALLS)
                .failureRateThreshold(BREAKER_FAILURE_RATE)
                .permittedNumberOfCallsInHalfOpenState(BREAKER_HALF_OPEN_CALLS)
                .waitDurationInOpenState(properties.resilience().breakerOpenDuration())
                // Only faults of the integration count. A 429 is the cool-down's business,
                // and a full bulkhead is our own limit, not evidence Wikipedia is unwell.
                .recordExceptions(UpstreamUnavailableException.class, UpstreamBadResponseException.class)
                .ignoreExceptions(UpstreamRateLimitedException.class, BulkheadFullException.class)
                .build());
    }

    @Bean
    public Retry wikipediaRetry(HistoryProperties properties, MeterRegistry meters) {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meters);
        return registry.retry(NAME, RetryConfig.custom()
                .maxAttempts(properties.resilience().retryAttempts())
                .waitDuration(properties.resilience().retryWait())
                .retryOnException(UpstreamUnavailableException.class::isInstance)
                .build());
    }

    @Bean
    public Bulkhead wikipediaBulkhead(HistoryProperties properties, MeterRegistry meters) {
        BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meters);
        return registry.bulkhead(NAME, BulkheadConfig.custom()
                .maxConcurrentCalls(properties.resilience().maxConcurrentCalls())
                .maxWaitDuration(properties.resilience().bulkheadMaxWait())
                .build());
    }

    /** The only WikipediaFeedClient bean: the plain HTTP client is not exposed, so nothing
     *  can reach Wikipedia without going through the decorators. */
    @Bean
    public WikipediaFeedClient wikipediaFeedClient(RestClient wikimediaRestClient, ObjectMapper objectMapper,
                                                   HistoryProperties properties, MeterRegistry meters, Clock clock,
                                                   CircuitBreaker wikipediaCircuitBreaker, Retry wikipediaRetry,
                                                   Bulkhead wikipediaBulkhead, UpstreamCooldown upstreamCooldown) {
        WikipediaFeedClient http = new WikimediaFeedHttpClient(wikimediaRestClient, objectMapper,
                properties.wikipedia(), meters, clock);
        return new ResilientWikipediaFeedClient(http, wikipediaCircuitBreaker, wikipediaRetry,
                wikipediaBulkhead, upstreamCooldown);
    }
}
