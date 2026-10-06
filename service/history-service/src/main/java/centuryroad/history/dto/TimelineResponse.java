package centuryroad.history.dto;

import centuryroad.history.model.Language;
import centuryroad.history.model.TimelineEvent;
import centuryroad.history.query.TimelineQuery;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * One country's events across the whole year, from the country index. The licence notice is
 * its own: unlike an on-this-day answer, these events carry no per-article links.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One country's events across the whole year, oldest first.")
public record TimelineResponse(
        @Schema(description = "ISO 3166-1 alpha-2 code of the country.", example = "IT") String countryCode,
        @Schema(description = "The Wikipedia edition the texts come from.") Language language,
        @Schema(description = "When the newest of these events was indexed. Absent when there are none.") OffsetDateTime indexedAt,
        @Schema(description = "The credit Wikipedia's licence requires wherever its text is shown.") OnThisDayResponse.Attribution attribution,
        @Schema(description = "The events, oldest first: by year, then month and day.") List<Event> events) {

    @Schema(description = "One event of the timeline.")
    public record Event(
            @Schema(description = "The year; negative before the common era.", example = "1908") int year,
            @Schema(description = "Month, 1 to 12.", example = "12") int month,
            @Schema(description = "Day of the month.", example = "28") int day,
            @Schema(description = "The event, as Wikipedia lists it on the page for that day.") String text) {
    }

    static final OnThisDayResponse.Attribution ATTRIBUTION = new OnThisDayResponse.Attribution(
            "Wikipedia",
            "CC BY-SA 4.0",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "Testi tratti da Wikipedia, nella lingua indicata, disponibili con licenza CC BY-SA 4.0. "
                    + "Ogni evento è elencato da Wikipedia nella pagina del suo giorno, con gli articoli che cita.");

    public static TimelineResponse from(TimelineQuery query, List<TimelineEvent> rows) {
        OffsetDateTime indexedAt = rows.stream()
                .map(TimelineEvent::getIndexedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        List<Event> events = rows.stream()
                .map(row -> new Event(row.getYear(), row.getMonth(), row.getDay(), row.getText()))
                .toList();
        return new TimelineResponse(query.countryCode(), query.language(), indexedAt, ATTRIBUTION, events);
    }
}
