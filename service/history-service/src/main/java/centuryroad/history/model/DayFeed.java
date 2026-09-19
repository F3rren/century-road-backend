package centuryroad.history.model;

import java.time.MonthDay;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** What one Wikipedia edition says about one calendar day, already cleaned up. */
public record DayFeed(Language language, MonthDay day, Map<Section, List<Entry>> sections) {

    public DayFeed {
        Map<Section, List<Entry>> copy = new EnumMap<>(Section.class);
        sections.forEach((section, entries) -> copy.put(section, List.copyOf(entries)));
        sections = Collections.unmodifiableMap(copy);
    }

    public List<Entry> entries(Section section) {
        return sections.getOrDefault(section, List.of());
    }
}
