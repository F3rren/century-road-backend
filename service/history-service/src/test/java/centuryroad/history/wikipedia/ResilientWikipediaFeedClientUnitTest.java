package centuryroad.history.wikipedia;

import centuryroad.history.TestClock;
import centuryroad.history.TestSupport;
import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.exception.UpstreamRateLimitedException;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Language;
import centuryroad.history.model.Section;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.MonthDay;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decorators around the real client, exercised against a scripted stand-in: each call to
 * it takes the next queued outcome, so a test can say "fail twice, then work" and count how
 * often Wikipedia would really have been asked.
 */
class ResilientWikipediaFeedClientUnitTest {

    private static final MonthDay DAY = MonthDay.of(10, 16);
    private static final DayFeed FEED = TestSupport.feed(Language.IT, DAY, Section.EVENTS,
            TestSupport.entry("x", 1900));

    private final TestClock clock = TestClock.atNoon();
    private final AtomicInteger calls = new AtomicInteger();
    private final Deque<Supplier<DayFeed>> script = new ArrayDeque<>();

    private final WikipediaFeedClient scripted = (language, day) -> {
        calls.incrementAndGet();
        Supplier<DayFeed> next = script.pollFirst();
        return next == null ? FEED : next.get();
    };

    private void thenFail(RuntimeException failure) {
        script.addLast(() -> {
            throw failure;
        });
    }

    private void thenSucceed() {
        script.addLast(() -> FEED);
    }

    private static UpstreamUnavailableException unavailable() {
        return new UpstreamUnavailableException("down", null);
    }

    private ResilientWikipediaFeedClient client(int retryAttempts, int breakerWindow, int maxConcurrent) {
        CircuitBreaker breaker = CircuitBreaker.of("t", CircuitBreakerConfig.custom()
                .slidingWindowSize(breakerWindow).minimumNumberOfCalls(breakerWindow).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(UpstreamUnavailableException.class, UpstreamBadResponseException.class)
                .ignoreExceptions(UpstreamRateLimitedException.class, BulkheadFullException.class).build());
        Retry retry = Retry.of("t", RetryConfig.custom().maxAttempts(retryAttempts)
                .waitDuration(Duration.ofMillis(1))
                .retryOnException(UpstreamUnavailableException.class::isInstance).build());
        Bulkhead bulkhead = Bulkhead.of("t", BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent).maxWaitDuration(Duration.ZERO).build());
        return new ResilientWikipediaFeedClient(scripted, breaker, retry, bulkhead, new UpstreamCooldown(clock));
    }

    // ----- retry ------------------------------------------------------------------------

    @Test
    void aTransientFailureIsRetriedOnce_andTheSecondTrySucceeds() {
        thenFail(unavailable());
        thenSucceed();

        assertThat(client(2, 10, 3).fetchDay(Language.IT, DAY)).isSameAs(FEED);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aFailureThatKeepsHappeningStopsAtTheConfiguredAttempts() {
        thenFail(unavailable());
        thenFail(unavailable());
        thenFail(unavailable());

        assertThatThrownBy(() -> client(2, 10, 3).fetchDay(Language.IT, DAY))
                .isInstanceOf(UpstreamUnavailableException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aMalformedAnswerIsNotRetried_becauseAskingAgainGetsTheSameAnswer() {
        thenFail(new UpstreamBadResponseException("garbage"));

        assertThatThrownBy(() -> client(3, 10, 3).fetchDay(Language.IT, DAY))
                .isInstanceOf(UpstreamBadResponseException.class);
        assertThat(calls).hasValue(1);
    }

    // ----- cool-down --------------------------------------------------------------------

    @Test
    void afterA429NothingIsSentUntilTheWaitIsOver() {
        thenFail(new UpstreamRateLimitedException(Duration.ofSeconds(30)));
        ResilientWikipediaFeedClient client = client(2, 10, 3);

        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY)).isInstanceOf(UpstreamRateLimitedException.class);
        assertThat(calls).hasValue(1);

        // Still inside the wait: refused locally, and Wikipedia is not asked.
        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> client.fetchDay(Language.EN, DAY))
                .isInstanceOf(UpstreamRateLimitedException.class)
                .satisfies(e -> assertThat(((UpstreamRateLimitedException) e).getRetryAfter())
                        .isEqualTo(Duration.ofSeconds(20)));
        assertThat(calls).hasValue(1);

        // Wait over: calls go through again.
        clock.advance(Duration.ofSeconds(21));
        assertThat(client.fetchDay(Language.IT, DAY)).isSameAs(FEED);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aRateLimitIsNotRetriedEither() {
        thenFail(new UpstreamRateLimitedException(Duration.ofSeconds(5)));

        assertThatThrownBy(() -> client(3, 10, 3).fetchDay(Language.IT, DAY))
                .isInstanceOf(UpstreamRateLimitedException.class);
        assertThat(calls).hasValue(1);
    }

    // ----- circuit breaker --------------------------------------------------------------

    @Test
    void afterARunOfFailuresTheCircuitOpensAndWikipediaIsLeftAlone() {
        ResilientWikipediaFeedClient client = client(1, 4, 3);
        for (int i = 0; i < 4; i++) {
            thenFail(unavailable());
            assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY)).isInstanceOf(UpstreamUnavailableException.class);
        }
        assertThat(calls).hasValue(4);

        // The fifth is refused without a request, and still reports the same kind of failure.
        assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("rest");
        assertThat(calls).hasValue(4);
    }

    @Test
    void rateLimitsDoNotCountTowardsOpeningTheCircuit() {
        ResilientWikipediaFeedClient client = client(1, 4, 3);
        for (int i = 0; i < 6; i++) {
            thenFail(new UpstreamRateLimitedException(Duration.ofMillis(1)));
            clock.advance(Duration.ofSeconds(1));
            assertThatThrownBy(() -> client.fetchDay(Language.IT, DAY)).isInstanceOf(UpstreamRateLimitedException.class);
        }

        thenSucceed();
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.fetchDay(Language.IT, DAY)).isSameAs(FEED);
    }

    // ----- bulkhead ---------------------------------------------------------------------

    @Test
    void whenTheOnlySlotIsBusyAnotherCallIsRefusedRightAway() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        script.addLast(() -> {
            inside.countDown();
            await(release);
            return FEED;
        });
        ResilientWikipediaFeedClient client = client(1, 10, 1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DayFeed> slow = pool.submit(() -> client.fetchDay(Language.IT, DAY));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> client.fetchDay(Language.EN, DAY))
                    .isInstanceOf(UpstreamUnavailableException.class)
                    .hasCauseInstanceOf(BulkheadFullException.class);
            assertThat(calls).hasValue(1);

            release.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).isSameAs(FEED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
