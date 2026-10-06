package centuryroad.history.service;

import centuryroad.history.TestClock;
import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The limits on "Segnala un errore": per visitor, for the whole service, and over time. */
class ReportRateLimiterUnitTest {

    private final TestClock clock = TestClock.atNoon();

    private ReportRateLimiter limiter(int perClient, int total) {
        return new ReportRateLimiter(new HistoryProperties(null, null, null, null, null,
                new HistoryProperties.Reports(perClient, total, Duration.ofHours(1))), clock);
    }

    @Test
    void aVisitorMaySendUpToTheLimit_thenIsRefusedWithHowLongToWait() {
        ReportRateLimiter limiter = limiter(2, 100);
        limiter.acquire("1.1.1.1");
        limiter.acquire("1.1.1.1");
        clock.advance(Duration.ofMinutes(20));

        assertThatThrownBy(() -> limiter.acquire("1.1.1.1"))
                .isInstanceOfSatisfying(TooManyRequestsException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("TOO_MANY_REQUESTS");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(40 * 60);
                });
    }

    @Test
    void oneVisitorBeingRefusedDoesNotAffectAnother() {
        ReportRateLimiter limiter = limiter(1, 100);
        limiter.acquire("1.1.1.1");

        assertThatThrownBy(() -> limiter.acquire("1.1.1.1")).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.acquire("2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void theWholeServiceHasALimitToo_soManyAddressesTogetherCannotFloodIt() {
        ReportRateLimiter limiter = limiter(5, 3);
        limiter.acquire("1.1.1.1");
        limiter.acquire("2.2.2.2");
        limiter.acquire("3.3.3.3");

        assertThatThrownBy(() -> limiter.acquire("4.4.4.4")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void aRefusedAttemptDoesNotUseUpAPlaceInTheServiceWideLimit() {
        ReportRateLimiter limiter = limiter(1, 2);
        limiter.acquire("1.1.1.1");
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> limiter.acquire("1.1.1.1")).isInstanceOf(TooManyRequestsException.class);
        }

        // Ten refusals later, there is still one place for somebody else.
        assertThatCode(() -> limiter.acquire("2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void whenTheWindowIsOverEverybodyStartsAgain() {
        ReportRateLimiter limiter = limiter(1, 1);
        limiter.acquire("1.1.1.1");
        assertThatThrownBy(() -> limiter.acquire("1.1.1.1")).isInstanceOf(TooManyRequestsException.class);

        clock.advance(Duration.ofHours(1));

        assertThatCode(() -> limiter.acquire("1.1.1.1")).doesNotThrowAnyException();
    }
}
