package centuryroad.history.service;

import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps "Segnala un errore" from being a way to fill the database: a fixed window per visitor
 * address, and one for the whole service so that many addresses together cannot do it either.
 *
 * In memory and per instance on purpose, the same trade-off as auth-service's login limiter:
 * the service runs one replica, and a shared store is the upgrade for the day it runs two.
 * A restart forgets the counts, which costs nothing for a limit that is there to stop floods.
 */
@Component
public class ReportRateLimiter {

    /** An address is never "*", so the service-wide window cannot collide with a visitor's. */
    private static final String SERVICE = "*";
    /** Past this many tracked addresses, the ones whose window is over are dropped. */
    private static final int PRUNE_ABOVE = 10_000;

    private record Window(Instant start, int count) {
    }

    private final HistoryProperties.Reports limits;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    public ReportRateLimiter(HistoryProperties properties, Clock clock) {
        this.limits = properties.reports();
        this.clock = clock;
    }

    /**
     * Counts one report from this address, or refuses it. A refused attempt is not counted, so a
     * visitor who is over the limit does not eat into everyone else's share of the service's.
     */
    public synchronized void acquire(String client) {
        Instant now = clock.instant();
        if (windows.size() > PRUNE_ABOVE) {
            windows.values().removeIf(window -> expired(window, now));
        }
        Window own = current(client, now);
        Window service = current(SERVICE, now);
        if (own.count() >= limits.maxPerClient()) {
            throw refusal("Too many reports from " + client, own, now);
        }
        if (service.count() >= limits.maxTotal()) {
            throw refusal("Too many reports in total", service, now);
        }
        windows.put(client, new Window(own.start(), own.count() + 1));
        windows.put(SERVICE, new Window(service.start(), service.count() + 1));
    }

    private Window current(String key, Instant now) {
        Window window = windows.get(key);
        return window == null || expired(window, now) ? new Window(now, 0) : window;
    }

    private boolean expired(Window window, Instant now) {
        return !now.isBefore(window.start().plus(limits.window()));
    }

    private TooManyRequestsException refusal(String message, Window window, Instant now) {
        Duration left = Duration.between(now, window.start().plus(limits.window()));
        long seconds = Math.max(1, (left.toMillis() + 999) / 1000);
        return new TooManyRequestsException(message,
                "Hai inviato troppe segnalazioni. Riprova più tardi.", seconds);
    }
}
