package centuryroad.history.service;

import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.UpstreamException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Language;
import centuryroad.history.wikipedia.WikipediaFeedClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.MonthDay;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * What stands between the users and Wikipedia. One entry per (language, day), so the number
 * of distinct requests this service can ever send upstream is bounded by 366 times the
 * number of languages, whatever traffic it gets.
 *
 * Three behaviours, all of which exist for Wikipedia's benefit as much as the users':
 *
 *   fresh    an entry younger than freshTtl is returned without a request. History barely
 *            changes, so this is hours, not the five minutes Wikipedia's own cache headers say.
 *   refresh  an older entry is refreshed; a hundred simultaneous callers for the same key
 *            produce ONE request, the others wait for its answer.
 *   stale    when the refresh fails, the old copy is served, flagged as stale, for up to
 *            maxStale. Old history is far better than an error page.
 *
 * Only entries that exist are ever served stale. A key never fetched successfully has
 * nothing to fall back on, and the failure is passed up.
 */
@Slf4j
@Component
public class FeedCache {

    /** A feed, and whether it is older than the freshness window. */
    public record Served(DayFeed feed, boolean stale) {
    }

    private record Key(Language language, MonthDay day) {
    }

    private record Stored(DayFeed feed, Instant fetchedAt) {
    }

    private final WikipediaFeedClient client;
    private final HistoryProperties.Cache settings;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Cache<Key, Stored> cache;
    private final ConcurrentMap<Key, CompletableFuture<Stored>> inFlight = new ConcurrentHashMap<>();

    public FeedCache(WikipediaFeedClient client, HistoryProperties properties, Clock clock, MeterRegistry meters) {
        this.client = client;
        this.settings = properties.cache();
        this.clock = clock;
        this.meters = meters;
        this.cache = Caffeine.newBuilder().maximumSize(settings.maxEntries()).recordStats().build();
        CaffeineCacheMetrics.monitor(meters, cache, "history_feed");
    }

    /** @throws UpstreamException when there is no fresh copy, Wikipedia cannot supply one,
     *  and there is no usable stale copy either */
    public Served get(Language language, MonthDay day) {
        Key key = new Key(language, day);
        Instant now = clock.instant();
        Stored stored = cache.getIfPresent(key);
        if (isFresh(stored, now)) {
            return new Served(stored.feed(), false);
        }
        try {
            return new Served(load(key).feed(), false);
        } catch (UpstreamException e) {
            if (stored != null && age(stored, now).compareTo(settings.maxStale()) < 0) {
                log.warn("Serving a {}h-old {} copy of {} because Wikipedia could not refresh it ({})",
                        age(stored, now).toHours(), language.code(), day, e.getErrorCode());
                meters.counter("history.stale.served", "language", language.code()).increment();
                return new Served(stored.feed(), true);
            }
            throw e;
        }
    }

    private Duration age(Stored stored, Instant now) {
        return Duration.between(stored.fetchedAt(), now);
    }

    private boolean isFresh(Stored stored, Instant now) {
        return stored != null && age(stored, now).compareTo(settings.freshTtl()) < 0;
    }

    /** Single-flight: the first caller for a key fetches, the rest wait on its future. */
    private Stored load(Key key) {
        CompletableFuture<Stored> mine = new CompletableFuture<>();
        CompletableFuture<Stored> leader = inFlight.putIfAbsent(key, mine);
        if (leader != null) {
            return await(leader);
        }
        try {
            // Checked again now that this caller is the leader: another one may have finished
            // between the first look at the cache and taking the lead, and asking Wikipedia
            // a second time for what has just been fetched is exactly what this class is for
            // preventing.
            Stored current = cache.getIfPresent(key);
            if (isFresh(current, clock.instant())) {
                mine.complete(current);
                return current;
            }
            Stored stored = new Stored(client.fetchDay(key.language(), key.day()), clock.instant());
            cache.put(key, stored);
            mine.complete(stored);
            return stored;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private Stored await(CompletableFuture<Stored> leader) {
        try {
            return leader.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw e;
        }
    }
}
