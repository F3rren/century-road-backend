package centuryroad.history.service;

import centuryroad.history.TestClock;
import centuryroad.history.TestSupport;
import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.exception.UpstreamException;
import centuryroad.history.exception.UpstreamRateLimitedException;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Entry;
import centuryroad.history.model.Language;
import centuryroad.history.model.OnThisDayResult;
import centuryroad.history.model.Section;
import centuryroad.history.model.SectionResult;
import centuryroad.history.query.OnThisDayQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.MonthDay;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fallback rules, one scenario each. The Wikipedia client is replaced by a table saying
 * what each language does; the cache in front of it is the real one, so stale copies and
 * one-request-per-language are tested as they actually behave.
 */
class OnThisDayServiceUnitTest {

    private static final MonthDay DAY = MonthDay.of(10, 16);

    private final TestClock clock = TestClock.atNoon();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Map<Language, Supplier<DayFeed>> behaviour = new EnumMap<>(Language.class);
    private final Map<Language, Integer> asked = new EnumMap<>(Language.class);

    private final OnThisDayService service = new OnThisDayService(
            new FeedCache((language, day) -> {
                asked.merge(language, 1, Integer::sum);
                return behaviour.get(language).get();
            }, TestSupport.properties(), clock, meters),
            TestSupport.properties(), meters);

    /** Italian as the live feed has it: events, selected and holidays, but no births or deaths. */
    private static DayFeed italian() {
        return TestSupport.feed(Language.IT, DAY, Map.of(
                Section.SELECTED, List.of(TestSupport.entry("it selected", 1978)),
                Section.EVENTS, List.of(TestSupport.entry("it event 1978", 1978), TestSupport.entry("it event 2006", 2006)),
                Section.HOLIDAYS, List.of(TestSupport.entry("it holiday", null))));
    }

    private static DayFeed english() {
        return TestSupport.feed(Language.EN, DAY, Map.of(
                Section.SELECTED, List.of(TestSupport.entry("en selected", 1384)),
                Section.EVENTS, List.of(TestSupport.entry("en event", 456)),
                Section.BIRTHS, List.of(TestSupport.entry("en birth 1351", 1351), TestSupport.entry("en birth 1978", 1978)),
                Section.DEATHS, List.of(TestSupport.entry("en death", 385)),
                Section.HOLIDAYS, List.of(TestSupport.entry("en holiday", null))));
    }

    private static Supplier<DayFeed> gives(DayFeed feed) {
        return () -> feed;
    }

    private static Supplier<DayFeed> fails(UpstreamException failure) {
        return () -> {
            throw failure;
        };
    }

    private static OnThisDayQuery ask(String lang) {
        return OnThisDayQuery.of(10, 16, lang, null, null, null, null);
    }

    private static List<String> texts(SectionResult section) {
        return section.items().stream().map(Entry::text).toList();
    }

    // ----- the normal case --------------------------------------------------------------

    @Test
    void whatTheLanguageAskedForHasComesFromIt_untouched() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, gives(english()));

        OnThisDayResult result = service.find(ask("it"));

        SectionResult events = result.sections().get(Section.EVENTS);
        assertThat(events.language()).isEqualTo(Language.IT);
        assertThat(events.fallback()).isFalse();
        assertThat(texts(events)).containsExactly("it event 1978", "it event 2006");
        assertThat(result.requestedLanguage()).isEqualTo(Language.IT);
    }

    @Test
    void aSectionTheLanguageLacksIsFilledFromTheFallback_andSaysSo() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, gives(english()));

        OnThisDayResult result = service.find(ask("it"));

        for (Section section : List.of(Section.BIRTHS, Section.DEATHS)) {
            SectionResult filled = result.sections().get(section);
            assertThat(filled.language()).isEqualTo(Language.EN);
            assertThat(filled.fallback()).isTrue();
            assertThat(filled.items()).isNotEmpty();
        }
        assertThat(result.warnings()).isEmpty();
        assertThat(result.degraded()).isFalse();
        assertThat(meters.get("history.fallback").tag("section", "births").tag("reason", "section_empty")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void theSectionsComeBackInTheOrderOfTheFeed() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, gives(english()));

        assertThat(service.find(ask("it")).sections().keySet()).containsExactly(Section.values());
    }

    @Test
    void theFallbackIsNotEvenAskedWhenTheLanguageHasEverythingRequested() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, gives(english()));

        service.find(OnThisDayQuery.of(10, 16, "it", List.of("events", "selected"), null, null, null));

        assertThat(asked).containsOnlyKeys(Language.IT);
    }

    @Test
    void askingForTheFallbackLanguageItselfNeverFetchesItTwice() {
        behaviour.put(Language.EN, gives(english()));

        OnThisDayResult result = service.find(ask("en"));

        assertThat(asked).containsEntry(Language.EN, 1).hasSize(1);
        assertThat(result.sections().values()).noneMatch(SectionResult::fallback);
    }

    // ----- year filter ------------------------------------------------------------------

    @Test
    void theYearFilterNarrowsWhatIsReturned() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, gives(english()));

        OnThisDayResult result = service.find(OnThisDayQuery.of(10, 16, "it", null, 1978, null, null));

        assertThat(texts(result.sections().get(Section.EVENTS))).containsExactly("it event 1978");
        assertThat(texts(result.sections().get(Section.BIRTHS))).containsExactly("en birth 1978");
    }

    @Test
    void aSectionEmptiedByTheYearFilterIsAnAnswer_notAGapToFillFromAnotherLanguage() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, gives(english()));

        // Italian has events, just none in 1200. English's 456 must not sneak in.
        OnThisDayResult result = service.find(
                OnThisDayQuery.of(10, 16, "it", List.of("events"), 1200, null, null));

        SectionResult events = result.sections().get(Section.EVENTS);
        assertThat(events.items()).isEmpty();
        assertThat(events.fallback()).isFalse();
        assertThat(events.language()).isEqualTo(Language.IT);
        assertThat(asked).containsOnlyKeys(Language.IT);
    }

    @Test
    void holidaysHaveNoYear_soAnyYearFilterLeavesThemOut() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, gives(english()));

        OnThisDayResult result = service.find(
                OnThisDayQuery.of(10, 16, "it", List.of("holidays"), null, 1900, 2100));

        assertThat(result.sections().get(Section.HOLIDAYS).items()).isEmpty();
    }

    // ----- the language asked for cannot be reached -------------------------------------

    @Test
    void ifTheLanguageAskedForIsDownEverySectionComesFromTheFallback_withAWarning() {
        behaviour.put(Language.IT, fails(new UpstreamUnavailableException("down", null)));
        behaviour.put(Language.EN, gives(english()));

        OnThisDayResult result = service.find(ask("it"));

        assertThat(result.sections().values()).allSatisfy(section -> {
            assertThat(section.language()).isEqualTo(Language.EN);
            assertThat(section.fallback()).isTrue();
        });
        assertThat(result.warnings()).containsExactly("PRIMARY_UNAVAILABLE");
        assertThat(result.degraded()).isTrue();
        assertThat(meters.get("history.fallback").tag("reason", "primary_failed").counters()).isNotEmpty();
    }

    @Test
    void ifNeitherCanBeReachedTheRequestFails_withTheFailureOfTheLanguageAskedFor() {
        UpstreamUnavailableException primaryFailure = new UpstreamUnavailableException("it down", null);
        behaviour.put(Language.IT, fails(primaryFailure));
        behaviour.put(Language.EN, fails(new UpstreamBadResponseException("en garbage")));

        assertThatThrownBy(() -> service.find(ask("it"))).isSameAs(primaryFailure);
    }

    @Test
    void aRateLimitOnTheOnlyLanguageIsReportedAsExactlyThat() {
        UpstreamRateLimitedException limited = new UpstreamRateLimitedException(Duration.ofSeconds(9));
        behaviour.put(Language.EN, fails(limited));

        assertThatThrownBy(() -> service.find(ask("en"))).isSameAs(limited);
    }

    // ----- the fallback cannot be reached -----------------------------------------------

    @Test
    void ifOnlyTheFallbackIsDownTheGapsStayEmpty_andAWarningSaysWhy() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, fails(new UpstreamUnavailableException("en down", null)));

        OnThisDayResult result = service.find(ask("it"));

        SectionResult births = result.sections().get(Section.BIRTHS);
        assertThat(births.items()).isEmpty();
        assertThat(births.fallback()).isFalse();
        assertThat(births.language()).isEqualTo(Language.IT);
        assertThat(texts(result.sections().get(Section.EVENTS))).isNotEmpty();
        assertThat(result.warnings()).containsExactly("FALLBACK_UNAVAILABLE");
    }

    @Test
    void whenTheLanguageIsFineAndTheFallbackFailsAnAllEmptyDayIsStillAnAnswer() {
        behaviour.put(Language.IT, gives(TestSupport.feed(Language.IT, DAY, Map.of())));
        behaviour.put(Language.EN, gives(TestSupport.feed(Language.EN, DAY, Map.of())));

        OnThisDayResult result = service.find(ask("it"));

        assertThat(result.sections().values()).allSatisfy(section -> assertThat(section.items()).isEmpty());
        assertThat(result.warnings()).isEmpty();
    }

    // ----- stale ------------------------------------------------------------------------

    @Test
    void aStaleCopyIsMarkedSectionBySection_andMakesTheAnswerDegraded() {
        behaviour.put(Language.IT, gives(italian()));
        behaviour.put(Language.EN, gives(english()));
        service.find(ask("it"));

        clock.advance(TestSupport.FRESH_TTL.plusHours(1));
        behaviour.put(Language.IT, fails(new UpstreamUnavailableException("down", null)));
        behaviour.put(Language.EN, fails(new UpstreamUnavailableException("down", null)));

        OnThisDayResult result = service.find(ask("it"));

        assertThat(result.sections().get(Section.EVENTS).stale()).isTrue();
        assertThat(result.sections().get(Section.BIRTHS).stale()).isTrue();
        assertThat(result.sections().get(Section.BIRTHS).language()).isEqualTo(Language.EN);
        assertThat(result.warnings()).isEmpty();
        assertThat(result.degraded()).isTrue();
    }
}
