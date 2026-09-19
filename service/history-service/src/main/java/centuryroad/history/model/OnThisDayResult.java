package centuryroad.history.model;

import java.time.MonthDay;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The service's answer, before it is dressed up for the wire. warnings are stable codes
 *  for things that went wrong without making the answer unusable. */
public record OnThisDayResult(MonthDay day, Language requestedLanguage,
                              Map<Section, SectionResult> sections, List<String> warnings) {

    public OnThisDayResult {
        // Copied into a LinkedHashMap, not Map.copyOf: the order the service built the
        // sections in is the order the JSON is written in.
        sections = Collections.unmodifiableMap(new LinkedHashMap<>(sections));
        warnings = List.copyOf(warnings);
    }

    /** True when the caller got something less good than a fresh copy in the language it
     *  asked for. Fallback languages do not count: that is a stable answer, not a fault. */
    public boolean degraded() {
        return !warnings.isEmpty() || sections.values().stream().anyMatch(SectionResult::stale);
    }
}
