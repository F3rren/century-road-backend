package centuryroad.history.service;

import centuryroad.history.TestClock;
import centuryroad.history.TestSupport;
import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.model.Coordinates;
import centuryroad.history.model.Entry;
import centuryroad.history.model.Language;
import centuryroad.history.model.PageRef;
import centuryroad.history.model.Section;
import centuryroad.history.model.TimelineEvent;
import centuryroad.history.repository.TimelineEventRepository;
import centuryroad.history.wikipedia.UpstreamCooldown;
import centuryroad.history.wikipedia.WikipediaFeedClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TimelineIndexerUnitTest {

    private static final CountryLocator LOCATOR = new CountryLocator(new ObjectMapper());
    private static final MonthDay AUGUST_8 = MonthDay.of(8, 8);
    private static final Coordinates ROME = new Coordinates(41.9028, 12.4964);
    private static final Coordinates PARIS = new Coordinates(48.8566, 2.3522);
    private static final Coordinates AT_SEA = new Coordinates(0, -30);

    private final TestClock clock = TestClock.atNoon();
    private final UpstreamCooldown cooldown = new UpstreamCooldown(clock);
    private final TimelineEventRepository repository = mock(TimelineEventRepository.class);
    private final List<Duration> pauses = new ArrayList<>();

    private static PageRef page(Coordinates coordinates) {
        return new PageRef("Pagina", null, null, "https://it.wikipedia.org/wiki/Pagina", null, null, coordinates, null);
    }

    private static Entry event(String text, Integer year, PageRef... pages) {
        return new Entry(text, year, List.of(pages));
    }

    private TimelineIndexer indexer(WikipediaFeedClient client, String cron, int maxConsecutiveFailures) {
        HistoryProperties defaults = TestSupport.properties();
        HistoryProperties properties = new HistoryProperties(defaults.fallbackLanguage(), defaults.cache(),
                defaults.wikipedia(), defaults.resilience(),
                new HistoryProperties.Timeline(cron, Duration.ofSeconds(1), maxConsecutiveFailures));
        return new TimelineIndexer(client, LOCATOR, repository, cooldown, properties, clock) {
            @Override
            boolean pause(Duration duration) {
                pauses.add(duration);
                clock.advance(duration);
                return true;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<TimelineEvent> replacedRows() {
        ArgumentCaptor<List<TimelineEvent>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceDay(eq("it"), eq(AUGUST_8), rows.capture());
        return rows.getValue();
    }

    // ----- one day -----------------------------------------------------------------------

    @Test
    void onlyEventsWithAYearPlacedInACountryBecomeRows_andFeaturedIsIgnored() {
        WikipediaFeedClient client = (language, day) -> TestSupport.feed(language, day, Map.of(
                Section.SELECTED, List.of(event("In evidenza a Roma", 1950, page(ROME))),
                Section.EVENTS, List.of(
                        event("A Roma", 1950, page(ROME)),
                        event("A Parigi", 1871, page(PARIS)),
                        event("Senza anno", null, page(ROME)),
                        event("Senza coordinate", 1900, page(null)),
                        event("Senza articoli", 1900))));

        assertThat(indexer(client, "-", 3).indexDay(Language.IT, AUGUST_8)).isTrue();

        assertThat(replacedRows()).extracting(TimelineEvent::getText, TimelineEvent::getCountryCode,
                        TimelineEvent::getYear)
                .containsExactly(tuple("A Roma", "IT", 1950), tuple("A Parigi", "FR", 1871));
    }

    @Test
    void onlyTheFirstArticleWithCoordinatesDecides_likeOnTheMap() {
        // The first located article is at sea: the event gets no country, even though the
        // next article is in Rome.
        WikipediaFeedClient client = (language, day) -> TestSupport.feed(language, day, Section.EVENTS,
                event("Battaglia navale", 1940, page(null), page(AT_SEA), page(ROME)));

        indexer(client, "-", 3).indexDay(Language.IT, AUGUST_8);

        assertThat(replacedRows()).isEmpty();
    }

    @Test
    void rowsCarryTheDayTheLanguageAndTheTimeTheyWereIndexed() {
        WikipediaFeedClient client = (language, day) -> TestSupport.feed(language, day, Section.EVENTS,
                event("A Roma", 1950, page(ROME)));

        indexer(client, "-", 3).indexDay(Language.IT, AUGUST_8);

        TimelineEvent row = replacedRows().get(0);
        assertThat(row.getLanguage()).isEqualTo("it");
        assertThat(row.getMonth()).isEqualTo((short) 8);
        assertThat(row.getDay()).isEqualTo((short) 8);
        assertThat(row.getIndexedAt()).isEqualTo(OffsetDateTime.now(clock));
    }

    @Test
    void aDayWithEventsButNonePlacedStillReplacesTheDay() {
        WikipediaFeedClient client = (language, day) -> TestSupport.feed(language, day, Section.EVENTS,
                event("Altrove", 1900, page(AT_SEA)));

        assertThat(indexer(client, "-", 3).indexDay(Language.IT, AUGUST_8)).isTrue();

        assertThat(replacedRows()).isEmpty();
    }

    @Test
    void aFeedWithNoEventsLeavesTheDayAsItWas() {
        WikipediaFeedClient client = (language, day) -> TestSupport.feed(language, day, Section.SELECTED,
                event("Solo in evidenza", 1950, page(ROME)));

        assertThat(indexer(client, "-", 3).indexDay(Language.IT, AUGUST_8)).isFalse();

        verify(repository, never()).replaceDay(anyString(), any(), anyList());
    }

    // ----- the pass ----------------------------------------------------------------------

    @Test
    void aDayWikipediaFailsOnKeepsItsRows_andThePassGoesOn() {
        WikipediaFeedClient client = (language, day) -> {
            if (day.equals(MonthDay.of(1, 1)) && language == Language.IT) {
                throw new UpstreamUnavailableException("down", null);
            }
            return TestSupport.feed(language, day, Section.EVENTS, event("A Roma", 1950, page(ROME)));
        };

        assertThat(indexer(client, "-", 3).rebuild()).isTrue();

        verify(repository, never()).replaceDay(eq("it"), eq(MonthDay.of(1, 1)), anyList());
        verify(repository).replaceDay(eq("en"), eq(MonthDay.of(1, 1)), anyList());
        verify(repository).replaceDay(eq("it"), eq(MonthDay.of(12, 31)), anyList());
        verify(repository).replaceDay(eq("it"), eq(MonthDay.of(2, 29)), anyList());
    }

    @Test
    void thePassStopsAfterTheConfiguredRunOfFailures() {
        AtomicInteger fetches = new AtomicInteger();
        WikipediaFeedClient client = (language, day) -> {
            fetches.incrementAndGet();
            throw new UpstreamUnavailableException("down", null);
        };

        indexer(client, "-", 3).rebuild();

        assertThat(fetches).hasValue(3);
    }

    @Test
    void requestsAreSpacedByTheConfiguredDelay() {
        AtomicInteger fetches = new AtomicInteger();
        WikipediaFeedClient client = (language, day) -> {
            if (fetches.incrementAndGet() > 2) {
                throw new UpstreamUnavailableException("down", null);
            }
            return TestSupport.feed(language, day, Section.EVENTS, event("A Roma", 1950, page(ROME)));
        };

        indexer(client, "-", 1).rebuild();

        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void aRunningCoolDownIsWaitedOutBeforeAskingWikipediaAgain() {
        cooldown.start(Duration.ofMinutes(2));
        List<Duration> remainingWhenAsked = new ArrayList<>();
        WikipediaFeedClient client = (language, day) -> {
            remainingWhenAsked.add(cooldown.remaining().orElse(Duration.ZERO));
            throw new UpstreamUnavailableException("down", null);
        };

        indexer(client, "-", 1).rebuild();

        assertThat(pauses.get(0)).isEqualTo(Duration.ofMinutes(2));
        assertThat(remainingWhenAsked).containsExactly(Duration.ZERO);
    }

    @Test
    void aSecondPassWhileOneIsRunningIsRefused() {
        AtomicBoolean secondPassStarted = new AtomicBoolean(true);
        TimelineIndexer[] self = new TimelineIndexer[1];
        WikipediaFeedClient client = (language, day) -> {
            secondPassStarted.set(self[0].rebuild());
            throw new UpstreamUnavailableException("down", null);
        };
        self[0] = indexer(client, "-", 1);

        assertThat(self[0].rebuild()).isTrue();
        assertThat(secondPassStarted).isFalse();
    }

    // ----- the build at startup ----------------------------------------------------------

    @Test
    void anEmptyIndexIsBuiltAtStartup() {
        AtomicInteger fetches = new AtomicInteger();
        WikipediaFeedClient client = (language, day) -> {
            fetches.incrementAndGet();
            throw new UpstreamUnavailableException("down", null);
        };
        given(repository.count()).willReturn(0L);

        indexer(client, "0 0 1 * * *", 1).buildIfEmpty();

        assertThat(fetches).hasValue(1);
    }

    @Test
    void anIndexThatHasRowsIsLeftForTheNightlyPass() {
        AtomicInteger fetches = new AtomicInteger();
        given(repository.count()).willReturn(42L);

        indexer((language, day) -> {
            fetches.incrementAndGet();
            throw new UpstreamUnavailableException("down", null);
        }, "0 0 1 * * *", 1).buildIfEmpty();

        assertThat(fetches).hasValue(0);
    }

    @Test
    void withTheJobSwitchedOffNothingIsBuiltAtStartup() {
        AtomicInteger fetches = new AtomicInteger();
        given(repository.count()).willReturn(0L);

        indexer((language, day) -> {
            fetches.incrementAndGet();
            throw new UpstreamUnavailableException("down", null);
        }, "-", 1).buildIfEmpty();

        assertThat(fetches).hasValue(0);
    }
}
