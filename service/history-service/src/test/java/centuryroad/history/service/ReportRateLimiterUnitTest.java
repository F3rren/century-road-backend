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

    @Test
    void everyAddressOfOneIpv6Slash64IsOneVisitor() {
        ReportRateLimiter limiter = limiter(1, 100);
        limiter.acquire("2001:db8:1:2::1");

        // A household or a phone holds a whole /64 and can write from any address in it: a fresh
        // quota per address would be no limit at all.
        assertThatThrownBy(() -> limiter.acquire("2001:db8:1:2:ffff:ffff:ffff:ffff"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatThrownBy(() -> limiter.acquire("2001:0db8:0001:0002:abcd::9"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void anotherIpv6Slash64IsAnotherVisitor() {
        ReportRateLimiter limiter = limiter(1, 100);
        limiter.acquire("2001:db8:1:2::1");

        assertThatCode(() -> limiter.acquire("2001:db8:1:3::1")).doesNotThrowAnyException();
    }

    @Test
    void anIpv4AddressWrittenInIpv6IsTheSameVisitorAsTheIpv4Address() {
        ReportRateLimiter limiter = limiter(1, 100);
        limiter.acquire("203.0.113.5");

        assertThatThrownBy(() -> limiter.acquire("::ffff:203.0.113.5")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void clientKeysAreWhatTheyAreDocumentedToBe() {
        assertThat(ReportRateLimiter.clientKey("203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(ReportRateLimiter.clientKey("2001:db8:1:2::1")).isEqualTo("2001:0db8:0001:0002::/64");
        assertThat(ReportRateLimiter.clientKey("fe80::1%eth0")).isEqualTo("fe80:0000:0000:0000::/64");
        // As a header writes it: in brackets, with or without a port.
        assertThat(ReportRateLimiter.clientKey("[2001:db8:1:2::1]")).isEqualTo("2001:0db8:0001:0002::/64");
        assertThat(ReportRateLimiter.clientKey("[2001:db8:1:2::1]:443")).isEqualTo("2001:0db8:0001:0002::/64");
        assertThat(ReportRateLimiter.clientKey(null)).isEqualTo("unknown");
        assertThat(ReportRateLimiter.clientKey("  ")).isEqualTo("unknown");
    }

    @Test
    void whatIsNotAnIpLiteralIsUsedAsItIs_andNeverLookedUp() {
        // It has colons, so it looks like a candidate; it has letters outside hex, so it is a host
        // name, and parsing it would be a DNS lookup on a value that came through headers.
        assertThat(ReportRateLimiter.clientKey("evil.example.com:8080")).isEqualTo("evil.example.com:8080");
        assertThat(ReportRateLimiter.clientKey("not:an:address:at:all:zzz")).isEqualTo("not:an:address:at:all:zzz");
        // Hex digits and colons only, but not a valid address either.
        assertThat(ReportRateLimiter.clientKey("1:2:3:4:5:6:7:8:9:a")).isEqualTo("1:2:3:4:5:6:7:8:9:a");
    }
}
