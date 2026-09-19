package centuryroad.history.wikipedia;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The stay-away period a 429 starts. While it runs, no request is sent to Wikipedia at all:
 * the callers fall through to a stale copy or the other language instead of waiting out a
 * Retry-After inside an HTTP request, and Wikipedia is not asked again before it said it
 * would be willing to answer. A later, longer cool-down extends the period, never shortens it.
 */
public class UpstreamCooldown {

    private final Clock clock;
    private final AtomicReference<Instant> until = new AtomicReference<>(Instant.MIN);

    public UpstreamCooldown(Clock clock) {
        this.clock = clock;
    }

    public void start(Duration duration) {
        Instant candidate = clock.instant().plus(duration);
        until.accumulateAndGet(candidate, (current, next) -> next.isAfter(current) ? next : current);
    }

    /** Time left, or empty when calls are allowed. */
    public Optional<Duration> remaining() {
        Duration left = Duration.between(clock.instant(), until.get());
        return left.isNegative() || left.isZero() ? Optional.empty() : Optional.of(left);
    }
}
