package centuryroad.history.dto;

import centuryroad.history.model.StartHerePick;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One proposal for a visitor who does not know where to begin. A path says how long it takes,
 * an insight says when it happened: whichever it is, the frontend can open it by type and slug.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One \"Inizia da qui\" proposal: a path or an insight chosen by hand.")
public record StartHereItem(
        @Schema(description = "PATH opens /api/history/paths/{slug}, INSIGHT opens /api/history/insights/{slug}.") StartHerePick.Type type,
        @Schema(description = "The identifier of the path or the insight.", example = "conquista-dello-spazio") String slug,
        @Schema(description = "Its title.") String title,
        @Schema(description = "Why to begin here, in a sentence.") String teaser,
        @Schema(description = "Present for a PATH: its card, with cover, reading time and number of stops.") PathSummary path,
        @Schema(description = "Present for an INSIGHT: the event in short, with its date and place.") InsightSummary insight) {

    public static StartHereItem ofPath(StartHerePick pick, PathSummary path) {
        return new StartHereItem(pick.type(), pick.slug(), path.title(), pick.teaser(), path, null);
    }

    public static StartHereItem ofInsight(StartHerePick pick, InsightSummary insight) {
        return new StartHereItem(pick.type(), pick.slug(), insight.title(), pick.teaser(), null, insight);
    }
}
