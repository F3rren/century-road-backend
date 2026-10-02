package centuryroad.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple in-memory rate limiter on login attempts, keyed by "ip|email" so one
 * address
 * exhausting its quota does not lock out somebody else typing the same address
 * by
 * coincidence. In-memory and per-instance on purpose: this service does not run
 * more
 * than one replica yet, and a shared store (Redis) is the upgrade to make the
 * day it
 * does, not before - see the classroom-backend project's own note on the same
 * tradeoff.
 */
@Component
public class LoginAttemptLimiter {

    private final int maxAttempts;
    private final long windowMs;
    private final ConcurrentHashMap<String, Attempts> attemptsByKey = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(
            @Value("${auth.rate-limit.max-attempts:5}") int maxAttempts,
            @Value("${auth.rate-limit.window-ms:60000}") long windowMs) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
    }

    /**
     * Returns the seconds to wait before retrying, or 0 if the attempt may proceed.
     */
    public long checkAndRecord(String key) {
        long now = System.currentTimeMillis();
        Attempts attempts = attemptsByKey.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart > windowMs) {
                return new Attempts(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (attempts.count.get() > maxAttempts) {
            long elapsed = now - attempts.windowStart;
            return Math.max(1, (windowMs - elapsed) / 1000);
        }
        return 0;
    }

    public void reset(String key) {
        attemptsByKey.remove(key);
    }

    /**
     * Drops every counter at once. This bean is a singleton for the whole
     * application
     * context, so an integration test that trips the limiter leaves it tripped for
     * whichever test runs next - clearing the database between tests does not undo
     * it.
     */
    public void clear() {
        attemptsByKey.clear();
    }

    private record Attempts(long windowStart, AtomicInteger count) {
    }
}
