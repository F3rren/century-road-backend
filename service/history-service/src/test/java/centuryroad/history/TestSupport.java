package centuryroad.history;

import centuryroad.history.config.HistoryProperties;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Entry;
import centuryroad.history.model.Language;
import centuryroad.history.model.Section;

import java.time.Duration;
import java.time.MonthDay;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builders for the objects nearly every test needs. */
public final class TestSupport {

    public static final Duration FRESH_TTL = Duration.ofHours(6);
    public static final Duration MAX_STALE = Duration.ofDays(7);

    private TestSupport() {
    }

    public static HistoryProperties.Wikipedia wikipediaSettings() {
        return new HistoryProperties.Wikipedia("http://wikipedia.invalid/{lang}", "Test/1",
                "https://example.invalid/contact", Duration.ofSeconds(2), Duration.ofSeconds(5),
                1_000_000, Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    public static HistoryProperties properties() {
        return new HistoryProperties(Language.EN,
                new HistoryProperties.Cache(FRESH_TTL, MAX_STALE, 100),
                wikipediaSettings(),
                new HistoryProperties.Resilience(3, Duration.ofMillis(50), 2, Duration.ofMillis(1),
                        Duration.ofSeconds(30)));
    }

    public static Entry entry(String text, Integer year) {
        return new Entry(text, year, List.of());
    }

    /** A feed with the given sections; any section not mentioned is empty. */
    public static DayFeed feed(Language language, MonthDay day, Map<Section, List<Entry>> sections) {
        return new DayFeed(language, day, new EnumMap<>(sections.isEmpty() ? new EnumMap<>(Section.class) : sections));
    }

    public static DayFeed feed(Language language, MonthDay day, Section section, Entry... entries) {
        return feed(language, day, Map.of(section, List.of(entries)));
    }
}
