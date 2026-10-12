package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A guided path as written, in src/main/resources/editorial/<topic>/paths: a small narrative
 * itinerary, not a category. Its stops open insights, in order. Neither the reading time nor
 * the years it spans are written here: they are counted from the words and read from the
 * stops' dates (see EditorialCatalog), so they cannot drift from the text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuidedPath(
        String slug,
        String title,
        String tagline,
        String intro,
        Topic topic,
        Cover cover,
        List<PathStop> stops) {

    public GuidedPath {
        stops = stops == null ? List.of() : List.copyOf(stops);
    }

    /**
     * The same path with its cover replaced. The one way to change a path's cover, so that a field
     * added to this record later is carried along instead of silently dropped by a hand-written
     * copy of the constructor.
     */
    public GuidedPath withCover(Cover newCover) {
        return new GuidedPath(slug, title, tagline, intro, topic, newCover, stops);
    }
}
