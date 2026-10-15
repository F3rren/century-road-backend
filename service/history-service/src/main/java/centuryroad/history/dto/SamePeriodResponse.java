package centuryroad.history.dto;

import centuryroad.history.model.Language;
import centuryroad.history.model.TimelineEvent;
import centuryroad.history.query.SamePeriodQuery;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Nello stesso periodo": the events of the country index in a window of years, by country.
 * It is a comparison in time and nothing else - events that happened around the same years in
 * different places - and says so in every answer, together with how thin the data under it
 * is. The index holds only what Wikipedia lists on its day pages and the map can place, so
 * a quiet window is far more often a gap in the data than a quiet world.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Events from the same few years in different countries: a comparison in time, not a chain of causes.")
public record SamePeriodResponse(
        @Schema(description = "The Wikipedia edition the texts come from.") Language language,
        @Schema(description = "The year asked about.", example = "1969") int year,
        @Schema(description = "First year of the window, inclusive.", example = "1964") int fromYear,
        @Schema(description = "Last year of the window, inclusive.", example = "1974") int toYear,
        @Schema(description = "The country left out, as asked. Absent when none was.", example = "IT") String excludedCountry,
        @Schema(description = "What these events are: TEMPORAL, always. They were contemporary; nothing says one led to another.") Comparison comparison,
        @Schema(description = "A sentence to show with the events, in Italian: they are a comparison in time, not causes and effects.") String notice,
        @Schema(description = "How much the index has for this window, and what it leaves out. Show the note.") Coverage coverage,
        @Schema(description = "The countries with events in the window, most events first. Each lists the ones closest to the year, oldest first.") List<CountryEvents> countries,
        @Schema(description = "The credit Wikipedia's licence requires wherever its text is shown.") OnThisDayResponse.Attribution attribution) {

    @Schema(description = "What kind of relation the events have. Only TEMPORAL exists, on purpose.")
    public enum Comparison {
        TEMPORAL
    }

    @Schema(description = "NONE: nothing in the window. SPARSE: too little to compare - fewer than 5 events or fewer than 3 countries. OK: enough, "
            + "though never complete.")
    public enum CoverageLevel {
        NONE,
        SPARSE,
        OK
    }

    @Schema(description = "How much the country index has for the window.")
    public record Coverage(
            @Schema(description = "NONE, SPARSE or OK.") CoverageLevel level,
            @Schema(description = "How many events of the window are in the index, after leaving out the excluded country.", example = "42") int eventCount,
            @Schema(description = "In how many countries.", example = "17") int countryCount,
            @Schema(description = "What the index leaves out, in Italian. Show it, whatever the level: even OK is a selection.") String note) {
    }

    @Schema(description = "One country's events in the window.")
    public record CountryEvents(
            @Schema(description = "ISO 3166-1 alpha-2 country code.", example = "JP") String countryCode,
            @Schema(description = "How many events the index has for this country in the whole window, of which `events` shows some.", example = "8") int eventCount,
            @Schema(description = "The events closest to the year, shown oldest first.") List<TimelineResponse.Event> events) {
    }

    static final String NOTICE = "Eventi avvenuti negli stessi anni in paesi diversi: un confronto nel tempo, "
            + "non una catena di cause ed effetti.";

    private static final String SELECTION = "L'elenco contiene solo gli eventi che Wikipedia riporta nelle pagine dei "
            + "singoli giorni e che la mappa riesce a collocare in un paese, circa la metà: non è tutto ciò che accadde nel mondo.";

    static final int SPARSE_BELOW_EVENTS = 5;
    static final int SPARSE_BELOW_COUNTRIES = 3;

    /** rows come ordered by country, then date, as the repository reads them. */
    public static SamePeriodResponse from(SamePeriodQuery query, List<TimelineEvent> rows) {
        Map<String, List<TimelineEvent>> byCountry = new LinkedHashMap<>();
        for (TimelineEvent row : rows) {
            if (!row.getCountryCode().equals(query.excludeCountry())) {
                byCountry.computeIfAbsent(row.getCountryCode(), code -> new ArrayList<>()).add(row);
            }
        }
        List<CountryEvents> countries = byCountry.entrySet().stream()
                .map(entry -> countryEvents(entry.getKey(), entry.getValue(), query))
                .sorted(Comparator.comparingInt(CountryEvents::eventCount).reversed()
                        .thenComparing(CountryEvents::countryCode))
                .toList();

        int eventCount = byCountry.values().stream().mapToInt(List::size).sum();
        return new SamePeriodResponse(query.language(), query.year(), query.fromYear(), query.toYear(),
                query.excludeCountry(), Comparison.TEMPORAL, NOTICE, coverage(eventCount, byCountry.size()), countries,
                TimelineResponse.ATTRIBUTION);
    }

    private static CountryEvents countryEvents(String code, List<TimelineEvent> rows, SamePeriodQuery query) {
        // The closest to the year first, so that cutting the list keeps the most pertinent;
        // then shown in the order they happened, which is how a timeline reads.
        List<TimelineResponse.Event> shown = rows.stream()
                .sorted(Comparator.comparingInt((TimelineEvent e) -> Math.abs(e.getYear() - query.year()))
                        .thenComparingInt(TimelineEvent::getYear)
                        .thenComparingInt(TimelineEvent::getMonth)
                        .thenComparingInt(TimelineEvent::getDay)
                        .thenComparing(TimelineEvent::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(query.perCountry())
                .sorted(Comparator.comparingInt(TimelineEvent::getYear)
                        .thenComparingInt(TimelineEvent::getMonth)
                        .thenComparingInt(TimelineEvent::getDay))
                .map(e -> new TimelineResponse.Event(e.getYear(), e.getMonth(), e.getDay(), e.getText()))
                .toList();
        return new CountryEvents(code, rows.size(), shown);
    }

    private static Coverage coverage(int eventCount, int countryCount) {
        if (eventCount == 0) {
            return new Coverage(CoverageLevel.NONE, 0, 0,
                    "Nell'indice non ci sono eventi per questo periodo. " + SELECTION);
        }
        boolean sparse = eventCount < SPARSE_BELOW_EVENTS || countryCount < SPARSE_BELOW_COUNTRIES;
        return new Coverage(sparse ? CoverageLevel.SPARSE : CoverageLevel.OK, eventCount, countryCount,
                (sparse ? "Pochi eventi per questo periodo: il confronto è poco significativo. " : "") + SELECTION);
    }
}
