package centuryroad.history.dto;

import centuryroad.history.model.Cover;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Topic;
import centuryroad.history.service.EditorialCatalog;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** A path as a card: what it is, how long it takes. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A guided path, in short: enough for a card on the home page or in the list of paths.")
public record PathSummary(
        @Schema(description = "The path's identifier, the last segment of its address.", example = "conquista-dello-spazio") String slug,
        @Schema(description = "The path's title.", example = "La conquista dello spazio") String title,
        @Schema(description = "One sentence on what the path is about.") String tagline,
        @Schema(description = "What the path is about, from a closed list. Paths are listed grouped by it, in the order the topics are declared.", example = "ESPLORAZIONI_E_SPAZIO") Topic topic,
        @Schema(description = "The topic's name to show, in Italian. A frontend may translate it, and falls back to this one for a topic it does not know.", example = "Esplorazioni e spazio") String topicLabel,
        @Schema(description = "The year of the path's earliest stop, read from the stops' dates. Negative before the common era.", example = "1957") int startYear,
        @Schema(description = "The year of the path's latest stop. Negative before the common era.", example = "2023") int endYear,
        @Schema(description = "The cover image. Absent when the path has none: the card has to work without it.") Cover cover,
        @Schema(description = "Estimated minutes to read the whole path, counted from its words.", example = "9") int readingMinutes,
        @Schema(description = "How many stops the path has.", example = "9") int stopCount) {

    public static PathSummary from(GuidedPath path, EditorialCatalog catalog) {
        return new PathSummary(path.slug(), path.title(), path.tagline(), path.topic(), path.topic().label(),
                catalog.startYear(path), catalog.endYear(path), path.cover(), catalog.readingMinutes(path),
                path.stops().size());
    }
}
