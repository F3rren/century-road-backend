package centuryroad.history.service;

import centuryroad.history.TestClock;
import centuryroad.history.TestSupport;
import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Language;
import centuryroad.history.model.Section;
import centuryroad.history.wikipedia.WikipediaFeedClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedCacheUnitTest {

    private static final MonthDay DAY = MonthDay.of(10, 16);

    private final TestClock clock = TestClock.atNoon();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile RuntimeException failure;

    private final WikipediaFeedClient client = (language, day) -> {
        calls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return TestSupport.feed(language, day, Section.EVENTS, TestSupport.entry("call " + calls.get(), 1900));
    };

    private final FeedCache cache = new FeedCache(client, TestSupport.properties(), clock, meters);

    private String textOf(FeedCache.Served served) {
        return served.feed().entries(Section.EVENTS).get(0).text();
    }

    // ----- fresh ------------------------------------------------------------------------

    @Test
    void theFirstCallAsksWikipedia_theNextOnesWithinTheFreshWindowDoNot() {
        FeedCache.Served first = cache.get(Language.IT, DAY);
        clock.advance(TestSupport.FRESH_TTL.minusMinutes(1));
        FeedCache.Served second = cache.get(Language.IT, DAY);

        assertThat(calls).hasValue(1);
        assertThat(first.stale()).isFalse();
        assertThat(second.feed()).isSameAs(first.feed());
        assertThat(second.stale()).isFalse();
    }

    @Test
    void eachLanguageAndEachDayIsItsOwnEntry() {
        cache.get(Language.IT, DAY);
        cache.get(Language.EN, DAY);
        cache.get(Language.IT, MonthDay.of(10, 17));
        cache.get(Language.IT, DAY);

        assertThat(calls).hasValue(3);
    }

    // ----- refresh ----------------------------------------------------------------------

    @Test
    void oncePastTheFreshWindowTheEntryIsRefreshed() {
        cache.get(Language.IT, DAY);
        clock.advance(TestSupport.FRESH_TTL.plusSeconds(1));

        FeedCache.Served refreshed = cache.get(Language.IT, DAY);

        assertThat(calls).hasValue(2);
        assertThat(textOf(refreshed)).isEqualTo("call 2");
        assertThat(refreshed.stale()).isFalse();
    }

    // ----- stale ------------------------------------------------------------------------

    @Test
    void whenTheRefreshFailsTheOldCopyIsServedAndSaidToBeStale() {
        cache.get(Language.IT, DAY);
        clock.advance(TestSupport.FRESH_TTL.plusHours(1));
        failure = new UpstreamUnavailableException("down", null);

        FeedCache.Served served = cache.get(Language.IT, DAY);

        assertThat(served.stale()).isTrue();
        assertThat(textOf(served)).isEqualTo("call 1");
        assertThat(meters.get("history.stale.served").tag("language", "it").counter().count()).isEqualTo(1);
    }

    @Test
    void aStaleCopyServesForAsLongAsMaxStaleAllows_andNotOneMomentLonger() {
        cache.get(Language.IT, DAY);
        failure = new UpstreamUnavailableException("down", null);

        clock.advance(TestSupport.MAX_STALE.minusMinutes(1));
        assertThat(cache.get(Language.IT, DAY).stale()).isTrue();

        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> cache.get(Language.IT, DAY)).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void theStaleCopyIsServedForAnyKindOfUpstreamFailure() {
        cache.get(Language.IT, DAY);
        clock.advance(TestSupport.FRESH_TTL.plusMinutes(1));
        failure = new UpstreamBadResponseException("garbage");

        assertThat(cache.get(Language.IT, DAY).stale()).isTrue();
    }

    @Test
    void oncePastTheWindowAgainTheNextSuccessfulRefreshIsFreshAgain() {
        cache.get(Language.IT, DAY);
        clock.advance(TestSupport.FRESH_TTL.plusMinutes(1));
        failure = new UpstreamUnavailableException("down", null);
        assertThat(cache.get(Language.IT, DAY).stale()).isTrue();

        failure = null;
        FeedCache.Served recovered = cache.get(Language.IT, DAY);

        assertThat(recovered.stale()).isFalse();
        assertThat(textOf(recovered)).isEqualTo("call 3");
    }

    // ----- nothing to fall back on ------------------------------------------------------

    @Test
    void aFailureWithNothingCachedIsPassedUp_andNotRememberedAsAnAnswer() {
        failure = new UpstreamUnavailableException("down", null);
        assertThatThrownBy(() -> cache.get(Language.IT, DAY)).isInstanceOf(UpstreamUnavailableException.class);

        failure = null;
        assertThat(cache.get(Language.IT, DAY).stale()).isFalse();
        assertThat(calls).hasValue(2);
    }

    // ----- single flight ----------------------------------------------------------------

    @Test
    void aCrowdAskingForTheSameDayAtOnceCostsWikipediaOneRequest() throws Exception {
        int callers = 20;
        CountDownLatch allStarted = new CountDownLatch(callers);
        AtomicInteger upstream = new AtomicInteger();
        WikipediaFeedClient slow = (language, day) -> {
            upstream.incrementAndGet();
            try {
                allStarted.await(5, TimeUnit.SECONDS);
                Thread.sleep(100);   // long enough for the others to pile up behind this call
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TestSupport.feed(language, day, Section.EVENTS, TestSupport.entry("shared", 1900));
        };
        FeedCache crowded = new FeedCache(slow, TestSupport.properties(), clock, new SimpleMeterRegistry());

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<FeedCache.Served>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    allStarted.countDown();
                    return crowded.get(Language.IT, DAY);
                }));
            }
            for (Future<FeedCache.Served> result : results) {
                assertThat(textOf(result.get(10, TimeUnit.SECONDS))).isEqualTo("shared");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(upstream).hasValue(1);
    }

    @Test
    void whenTheOneRequestFailsEveryoneWaitingOnItSeesTheSameFailure() throws Exception {
        int callers = 8;
        CountDownLatch allStarted = new CountDownLatch(callers);
        AtomicInteger upstream = new AtomicInteger();
        WikipediaFeedClient failing = (language, day) -> {
            upstream.incrementAndGet();
            try {
                allStarted.await(5, TimeUnit.SECONDS);
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new UpstreamUnavailableException("down", null);
        };
        FeedCache crowded = new FeedCache(failing, TestSupport.properties(), clock, new SimpleMeterRegistry());

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    allStarted.countDown();
                    return crowded.get(Language.IT, DAY);
                }));
            }
            for (Future<?> result : results) {
                assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(UpstreamUnavailableException.class);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(upstream).hasValue(1);
    }

    @Test
    void theOutcomeIsAFeedForTheRightDayAndLanguage() {
        DayFeed feed = cache.get(Language.EN, MonthDay.of(2, 29)).feed();

        assertThat(feed.language()).isEqualTo(Language.EN);
        assertThat(feed.day()).isEqualTo(MonthDay.of(2, 29));
    }
}
