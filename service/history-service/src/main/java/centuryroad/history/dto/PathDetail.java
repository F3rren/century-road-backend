package centuryroad.history.dto;

import centuryroad.history.model.Cover;
import centuryroad.history.model.EventDate;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Language;
import centuryroad.history.model.Place;
import centuryroad.history.service.EditorialCatalog;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * A path, whole: its introduction and its stops in order. A stop carries what the map needs
 * to move (the place) and the line that ties it to the path; the full "Perché conta" is one
 * request away, by the stop's slug, and is only asked for when the stop is opened.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A guided path: an introduction and its stops, in the order to visit them.")
public record PathDetail(
        @Schema(description = "The path's identifier.", example = "conquista-dello-spazio") String slug,
        @Schema(description = "The language of every text here. Editorial content is written in Italian only.") Language language,
        @Schema(description = "The path's title.") String title,
        @Schema(description = "One sentence on what the path is about.") String tagline,
        @Schema(description = "A short introduction: where the path starts and what it follows.") String intro,
        @Schema(description = "The cover image. Absent when the path has none.") Cover cover,
        @Schema(description = "Estimated minutes to read the whole path, counted from its words.", example = "9") int readingMinutes,
        @Schema(description = "The stops, in order. Open the first, then step to the previous or the next by position.") List<Stop> stops) {

    @Schema(description = "One stop of a path.")
    public record Stop(
            @Schema(description = "Its place in the path, counting from 1.", example = "1") int position,
            @Schema(description = "The insight this stop opens: ask for it at /api/history/insights/{slug} to show the stop's content.", example = "sputnik-1") String slug,
            @Schema(description = "The event's title.") String title,
            @Schema(description = "The event in one or two sentences.") String summary,
            @Schema(description = "When it happened.") EventDate date,
            @Schema(description = "Where the map should go. Check `approximate`.") Place place,
            @Schema(description = "Why the path comes here, from the stop before: the thread that makes the stops a story.") String narrative) {
    }

    public static PathDetail from(GuidedPath path, EditorialCatalog catalog) {
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < path.stops().size(); i++) {
            var stop = path.stops().get(i);
            var insight = catalog.insight(stop.insight()).orElseThrow();
            stops.add(new Stop(i + 1, insight.slug(), insight.title(), insight.summary(), insight.date(),
                    insight.place(), stop.narrative()));
        }
        return new PathDetail(path.slug(), Language.IT, path.title(), path.tagline(), path.intro(), path.cover(),
                catalog.readingMinutes(path), stops);
    }
}
