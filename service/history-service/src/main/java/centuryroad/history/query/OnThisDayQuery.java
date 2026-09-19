package centuryroad.history.query;

import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.model.Language;
import centuryroad.history.model.Section;

import java.time.DateTimeException;
import java.time.MonthDay;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the caller asked for, validated. Wikipedia does no validation of its own - it answers
 * 200 with empty lists for 13/40 and for 30 February - so every check that matters happens
 * here, before anything is sent upstream. MonthDay accepts 29 February, which is right: the
 * feed has entries for it.
 */
public record OnThisDayQuery(MonthDay day, Language language, Set<Section> sections, YearRange years) {

    static final int MIN_YEAR = -9999;
    static final int MAX_YEAR = 9999;

    public OnThisDayQuery {
        sections = Set.copyOf(sections);
    }

    public static OnThisDayQuery of(int month, int day, String lang, List<String> types,
                                    Integer year, Integer fromYear, Integer toYear) {
        return new OnThisDayQuery(parseDay(month, day), parseLanguage(lang), parseSections(types),
                parseYears(year, fromYear, toYear));
    }

    private static MonthDay parseDay(int month, int day) {
        try {
            return MonthDay.of(month, day);
        } catch (DateTimeException e) {
            throw new InvalidRequestException("INVALID_DATE", "No such day: " + month + "/" + day,
                    "La data indicata non esiste.");
        }
    }

    private static Language parseLanguage(String lang) {
        return Language.fromCode(lang).orElseThrow(() -> new InvalidRequestException(
                "UNSUPPORTED_LANGUAGE", "Unsupported language",
                "Lingua non supportata. Lingue disponibili: " + Language.supportedCodes() + "."));
    }

    private static Set<Section> parseSections(List<String> types) {
        if (types == null || types.isEmpty()) {
            return EnumSet.allOf(Section.class);
        }
        Set<Section> sections = EnumSet.noneOf(Section.class);
        for (String type : types) {
            sections.add(Section.fromKey(type.trim()).orElseThrow(() -> new InvalidRequestException(
                    "INVALID_TYPE", "Unknown type",
                    "Tipo non valido. Tipi disponibili: " + Section.supportedKeys() + ".")));
        }
        return sections;
    }

    private static YearRange parseYears(Integer year, Integer fromYear, Integer toYear) {
        if (year != null && (fromYear != null || toYear != null)) {
            throw invalidYears("year cannot be combined with fromYear/toYear",
                    "Indica un anno preciso oppure un intervallo, non entrambi.");
        }
        Integer from = year != null ? year : fromYear;
        Integer to = year != null ? year : toYear;
        if (from == null && to == null) {
            return YearRange.unbounded();
        }
        int lower = Optional.ofNullable(from).orElse(MIN_YEAR);
        int upper = Optional.ofNullable(to).orElse(MAX_YEAR);
        if (lower < MIN_YEAR || upper > MAX_YEAR) {
            throw invalidYears("year out of range",
                    "L'anno deve essere compreso tra " + MIN_YEAR + " e " + MAX_YEAR + ".");
        }
        if (lower > upper) {
            throw invalidYears("fromYear is after toYear",
                    "L'anno iniziale non puo' essere successivo a quello finale.");
        }
        return new YearRange(lower, upper);
    }

    private static InvalidRequestException invalidYears(String message, String userMessage) {
        return new InvalidRequestException("INVALID_YEAR", message, userMessage);
    }
}
