package centuryroad.history.dto;

import centuryroad.history.model.Language;
import centuryroad.history.model.TimelineEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Optional;

/**
 * "Sorprendimi": one event picked at random from the country index, with where and when, so
 * a frontend can open the day (and select the country) it belongs to. The text is the index's
 * own; the day's full entry, with its articles and images, is one request to the on-this-day
 * endpoint away.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A random event from the country index, or none when nothing matches the filters.")
public record RandomEventResponse(
        @Schema(description = "The Wikipedia edition the text comes from.") Language language,
        @Schema(description = "The event. Absent when no event of the index matches the filters: say so, and do not retry with the same ones.") Event event,
        @Schema(description = "The credit Wikipedia's licence requires wherever its text is shown.") OnThisDayResponse.Attribution attribution) {

    @Schema(description = "One event of the country index.")
    public record Event(
            @Schema(description = "The year; negative before the common era.", example = "1908") int year,
            @Schema(description = "Month, 1 to 12.", example = "12") int month,
            @Schema(description = "Day of the month.", example = "28") int day,
            @Schema(description = "ISO 3166-1 alpha-2 code of the country the map places it in.", example = "IT") String countryCode,
            @Schema(description = "The event, as Wikipedia lists it on the page for that day.") String text) {
    }

    public static RandomEventResponse from(Language language, Optional<TimelineEvent> row) {
        Event event = row.map(r -> new Event(r.getYear(), r.getMonth(), r.getDay(), r.getCountryCode(), r.getText()))
                .orElse(null);
        return new RandomEventResponse(language, event, TimelineResponse.ATTRIBUTION);
    }
}
