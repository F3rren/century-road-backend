package centuryroad.history.dto;

import centuryroad.history.model.EventDate;
import centuryroad.history.model.Insight;
import centuryroad.history.model.Place;
import io.swagger.v3.oas.annotations.media.Schema;

/** What a list of insights - or a path's stop - needs: enough to show a card and move the map. */
@Schema(description = "An insight, in short: enough for a card and for the map to move to its place.")
public record InsightSummary(
        @Schema(description = "The insight's identifier, the last segment of its address.", example = "sputnik-1") String slug,
        @Schema(description = "The event's title.", example = "Lo Sputnik 1 entra in orbita") String title,
        @Schema(description = "The event in one or two sentences.") String summary,
        @Schema(description = "When it happened.") EventDate date,
        @Schema(description = "Where the map should go.") Place place) {

    public static InsightSummary from(Insight insight) {
        return new InsightSummary(insight.slug(), insight.title(), insight.summary(), insight.date(), insight.place());
    }
}
