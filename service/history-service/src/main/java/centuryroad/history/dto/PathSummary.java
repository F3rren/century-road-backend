package centuryroad.history.dto;

import centuryroad.history.model.Cover;
import centuryroad.history.model.GuidedPath;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** A path as a card: what it is, how long it takes. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A guided path, in short: enough for a card on the home page or in the list of paths.")
public record PathSummary(
        @Schema(description = "The path's identifier, the last segment of its address.", example = "conquista-dello-spazio") String slug,
        @Schema(description = "The path's title.", example = "La conquista dello spazio") String title,
        @Schema(description = "One sentence on what the path is about.") String tagline,
        @Schema(description = "The cover image. Absent when the path has none: the card has to work without it.") Cover cover,
        @Schema(description = "Estimated minutes to read the whole path, counted from its words.", example = "9") int readingMinutes,
        @Schema(description = "How many stops the path has.", example = "9") int stopCount) {

    public static PathSummary from(GuidedPath path, int readingMinutes) {
        return new PathSummary(path.slug(), path.title(), path.tagline(), path.cover(), readingMinutes,
                path.stops().size());
    }
}
