package centuryroad.history.service;

import centuryroad.history.config.HistoryProperties;
import centuryroad.history.exception.UpstreamException;
import centuryroad.history.model.Entry;
import centuryroad.history.model.Language;
import centuryroad.history.model.OnThisDayResult;
import centuryroad.history.model.Section;
import centuryroad.history.model.SectionResult;
import centuryroad.history.query.OnThisDayQuery;
import centuryroad.history.query.YearRange;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers a query, and is the single place that decides what to do when Wikipedia's answer
 * is not what was asked for. The rules, in one paragraph:
 *
 * Take each requested section from the language asked for. A section that language has no
 * entries for - the Italian feed has no births or deaths at all - is filled from the
 * fallback language instead, and marked as such so the caller can say so. If the language
 * asked for cannot be fetched at all, every section comes from the fallback. Only when
 * neither can be reached does the request fail, with the failure of the language asked for.
 *
 * The decision to fall back looks at what Wikipedia returned, never at what is left after
 * the year filter: "nothing happened in 1200 on this day" is an answer, not a gap to fill
 * from another language.
 */
@Service
public class OnThisDayService {

    private final FeedCache feeds;
    private final HistoryProperties properties;
    private final MeterRegistry meters;

    public OnThisDayService(FeedCache feeds, HistoryProperties properties, MeterRegistry meters) {
        this.feeds = feeds;
        this.properties = properties;
        this.meters = meters;
    }

    public OnThisDayResult find(OnThisDayQuery query) {
        Language requested = query.language();
        Language fallbackLanguage = properties.fallbackLanguage();

        Load primary = load(requested, query.day());
        boolean fallbackWanted = fallbackLanguage != requested
                && (primary.failed() || primary.lacksAnyOf(query.sections()));
        Load fallback = fallbackWanted ? load(fallbackLanguage, query.day()) : Load.skipped();

        if (primary.failed() && !fallback.succeeded()) {
            throw primary.failure();
        }

        Map<Section, SectionResult> sections = new EnumMap<>(Section.class);
        query.sections().stream().sorted(Comparator.naturalOrder())
                .forEach(section -> sections.put(section, pick(section, primary, fallback, query.years())));
        return new OnThisDayResult(query.day(), requested, sections, warnings(primary, fallback, fallbackWanted));
    }

    private SectionResult pick(Section section, Load primary, Load fallback, YearRange years) {
        if (primary.succeeded() && !primary.entries(section).isEmpty()) {
            return new SectionResult(primary.language(), false, primary.stale(), years.apply(primary.entries(section)));
        }
        if (fallback.succeeded() && !fallback.entries(section).isEmpty()) {
            meters.counter("history.fallback", "section", section.key(),
                    "reason", primary.failed() ? "primary_failed" : "section_empty").increment();
            return new SectionResult(fallback.language(), true, fallback.stale(), years.apply(fallback.entries(section)));
        }
        // Empty in every language that could be reached: report it as what it is.
        Load shown = primary.succeeded() ? primary : fallback;
        return new SectionResult(shown.language(), !primary.succeeded(), shown.stale(), List.of());
    }

    private List<String> warnings(Load primary, Load fallback, boolean fallbackWanted) {
        List<String> warnings = new ArrayList<>();
        if (primary.failed()) {
            warnings.add("PRIMARY_UNAVAILABLE");
        } else if (fallbackWanted && fallback.failed()) {
            warnings.add("FALLBACK_UNAVAILABLE");
        }
        return warnings;
    }

    private Load load(Language language, MonthDay day) {
        try {
            return new Load(language, feeds.get(language, day), null);
        } catch (UpstreamException e) {
            return new Load(language, null, e);
        }
    }

    /** The outcome of trying one language. All three fields null means "not attempted". */
    private record Load(Language language, FeedCache.Served served, UpstreamException failure) {

        static Load skipped() {
            return new Load(null, null, null);
        }

        boolean succeeded() {
            return served != null;
        }

        boolean failed() {
            return failure != null;
        }

        boolean stale() {
            return served != null && served.stale();
        }

        List<Entry> entries(Section section) {
            return served == null ? List.of() : served.feed().entries(section);
        }

        boolean lacksAnyOf(Set<Section> sections) {
            return sections.stream().anyMatch(section -> entries(section).isEmpty());
        }
    }
}
