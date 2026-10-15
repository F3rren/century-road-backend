package centuryroad.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterUnitTest {

    @Test
    void staysOpenUpToTheConfiguredLimit() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, 60_000L);

        assertThat(limiter.checkAndRecord("k")).isZero();
        assertThat(limiter.checkAndRecord("k")).isZero();
        assertThat(limiter.checkAndRecord("k")).isZero();
    }

    @Test
    void blocksOnceTheLimitIsExceeded() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, 60_000L);

        limiter.checkAndRecord("k");
        limiter.checkAndRecord("k");

        assertThat(limiter.checkAndRecord("k")).isGreaterThan(0);
    }

    @Test
    void twoDifferentKeysDoNotShareAQuota() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 60_000L);

        limiter.checkAndRecord("ip1|a@test.it");

        assertThat(limiter.checkAndRecord("ip2|a@test.it")).isZero();
    }

    @Test
    void clearDropsTheCountersOfEveryKey() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 60_000L);

        limiter.checkAndRecord("ip|a@test.it");
        limiter.checkAndRecord("ip|b@test.it");
        limiter.clear();

        assertThat(limiter.checkAndRecord("ip|a@test.it")).isZero();
        assertThat(limiter.checkAndRecord("ip|b@test.it")).isZero();
    }

    @Test
    void resetClearsTheCounter() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 60_000L);

        limiter.checkAndRecord("k");
        limiter.reset("k");

        assertThat(limiter.checkAndRecord("k")).isZero();
    }

    @Test
    void counterWhoseWindowIsOverAreDroppedOnceTooManyKeysAreTracked() {
        long[] now = {1_000L};
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, 60_000L, () -> now[0]);
        // Every key a caller can invent is a key kept: this many, all fresh.
        for (int i = 0; i <= LoginAttemptLimiter.PRUNE_ABOVE; i++) {
            limiter.checkAndRecord("ip|user" + i + "@test.it");
        }
        assertThat(limiter.trackedKeys()).isEqualTo(LoginAttemptLimiter.PRUNE_ABOVE + 1);

        now[0] += 60_001L;
        limiter.checkAndRecord("ip|someone-else@test.it");

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void countersStillInsideTheirWindowSurviveThePruning() {
        long[] now = {1_000L};
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 60_000L, () -> now[0]);
        for (int i = 0; i <= LoginAttemptLimiter.PRUNE_ABOVE; i++) {
            limiter.checkAndRecord("ip|user" + i + "@test.it");
        }

        now[0] += 30_000L;
        limiter.checkAndRecord("ip|someone-else@test.it");

        // Nothing was old enough to drop, and a key already at its limit is still at it.
        assertThat(limiter.trackedKeys()).isEqualTo(LoginAttemptLimiter.PRUNE_ABOVE + 2);
        assertThat(limiter.checkAndRecord("ip|user0@test.it")).isGreaterThan(0);
    }
}
