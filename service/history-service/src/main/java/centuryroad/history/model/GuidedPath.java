package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A guided path as written, in src/main/resources/editorial/paths: a small narrative
 * itinerary, not a category. Its stops open insights, in order; the reading time is not
 * written here, it is counted from the words (see EditorialCatalog).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuidedPath(
        String slug,
        String title,
        String tagline,
        String intro,
        Cover cover,
        List<PathStop> stops) {

    public GuidedPath {
        stops = stops == null ? List.of() : List.copyOf(stops);
    }
}
