package centuryroad.history.service;

import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.UpstreamException;
import centuryroad.history.model.Entry;
import centuryroad.history.model.Language;
import centuryroad.history.model.PageRef;
import centuryroad.history.model.Section;
import centuryroad.history.model.TimelineEvent;
import centuryroad.history.repository.TimelineEventRepository;
import centuryroad.history.wikipedia.UpstreamCooldown;
import centuryroad.history.wikipedia.WikipediaFeedClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the country index behind "Il mio secolo": every day of the year in both editions,
 * one request at a time, each event placed in a country by the map's own rule.
 *
 * It calls the resilient Wikipedia client directly, never FeedCache: the cache keeps whatever
 * it is given (it only evicts by size), so a whole year through it would pin a few hundred MB
 * of feeds in memory for good. The client still brings the bulkhead, retry, circuit breaker
 * and 429 cool-down, all shared with visitors' requests - which is why the pass is sequential,
 * paced, waits out a cool-down before asking again, and gives up after a run of failures.
 * Only the events section, from each edition alone: no selected (it repeats events), no
 * fallback from one language to the other.
 */
// ponytail: fetches the "all" feed like the rest of the service, though only events are kept;
// Wikimedia's events-only endpoint is ~3.4x smaller (measured 68 KB vs 227 KB gzipped) - add a
// per-section fetch to the client if the pass's load or its ~80 minutes ever matter.
@Service
public class TimelineIndexer {

    private static final Logger log = LoggerFactory.getLogger(TimelineIndexer.class);

    private final WikipediaFeedClient wikipedia;
    private final CountryLocator locator;
    private final TimelineEventRepository repository;
    private final UpstreamCooldown cooldown;
    private final HistoryProperties.Timeline settings;
    private final Clock clock;
    // ponytail: guards one JVM (Railway runs one replica); ShedLock (a table + a dependency) if it ever runs more
    private final AtomicBoolean running = new AtomicBoolean();

    public TimelineIndexer(WikipediaFeedClient wikipedia, CountryLocator locator, TimelineEventRepository repository,
            UpstreamCooldown cooldown, HistoryProperties properties, Clock clock) {
        this.wikipedia = wikipedia;
        this.locator = locator;
        this.repository = repository;
        this.cooldown = cooldown;
        this.settings = properties.timeline();
        this.clock = clock;
    }

    @Scheduled(cron = "${history.timeline.cron}", zone = "UTC")
    public void nightly() {
        rebuild();
    }

    // A first deploy (or a TRUNCATE) would otherwise leave the index empty until the night.
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void buildIfEmpty() {
        if (!Scheduled.CRON_DISABLED.equals(settings.cron()) && repository.count() == 0) {
            log.info("The country index is empty: building it now");
            rebuild();
        }
    }

    /** One pass over the year. False when another pass is already running. */
    boolean rebuild() {
        if (!running.compareAndSet(false, true)) {
            log.info("A country index pass is already running: this one is skipped");
            return false;
        }
        Instant started = clock.instant();
        int refreshed = 0;
        int kept = 0;
        int failuresInARow = 0;
        try {
            for (MonthDay day : daysOfTheYear()) {
                for (Language language : Language.values()) {
                    Optional<Duration> coolingDown = cooldown.remaining();
                    if (coolingDown.isPresent() && !pause(coolingDown.get())) {
                        return true;
                    }
                    boolean done;
                    try {
                        done = indexDay(language, day);
                    } catch (UpstreamException e) {
                        log.debug("Country index: {} {} kept, Wikipedia failed: {}", language.code(), day, e.getMessage());
                        done = false;
                    }
                    if (done) {
                        refreshed++;
                        failuresInARow = 0;
                    } else {
                        kept++;
                        failuresInARow++;
                    }
                    if (failuresInARow >= settings.maxConsecutiveFailures()) {
                        log.warn("Country index pass stopped: {} days in a row could not be refreshed "
                                + "({} refreshed, {} kept as they were)", failuresInARow, refreshed, kept);
                        return true;
                    }
                    if (!pause(settings.delay())) {
                        return true;
                    }
                }
            }
            log.info("Country index pass done: {} days refreshed, {} kept as they were, {} rows, took {}",
                    refreshed, kept, repository.count(), Duration.between(started, clock.instant()));
            return true;
        } finally {
            running.set(false);
        }
    }

    /**
     * Fetches one day of one edition and replaces that day's rows. False, leaving the rows as
     * they were, when the feed has no events at all: that is a glitch, not history.
     */
    public boolean indexDay(Language language, MonthDay day) {
        List<Entry> events = wikipedia.fetchDay(language, day).entries(Section.EVENTS);
        if (events.isEmpty()) {
            log.debug("Country index: {} {} kept, the feed has no events", language.code(), day);
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<TimelineEvent> rows = new ArrayList<>();
        for (Entry event : events) {
            if (event.year() == null) {
                continue;
            }
            countryOf(event).ifPresent(code -> rows.add(new TimelineEvent(null, language.code(),
                    (short) day.getMonthValue(), (short) day.getDayOfMonth(), event.year(), code, event.text(), now)));
        }
        repository.replaceDay(language.code(), day, rows);
        log.debug("Country index: {} {}, {} of {} events placed in a country", language.code(), day, rows.size(),
                events.size());
        return true;
    }

    // The map's rule exactly (geocodeEntries.ts): the first linked article with coordinates,
    // and only that one - if it falls in no country, later articles are not tried.
    private Optional<String> countryOf(Entry event) {
        return event.pages().stream()
                .map(PageRef::coordinates)
                .filter(Objects::nonNull)
                .findFirst()
                .flatMap(locator::locate);
    }

    /** Sleeps; false when interrupted (the service is stopping), which ends the pass. */
    boolean pause(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // A leap year, so 29 February is in.
    private static List<MonthDay> daysOfTheYear() {
        return LocalDate.of(2000, 1, 1).datesUntil(LocalDate.of(2001, 1, 1)).map(MonthDay::from).toList();
    }
}
