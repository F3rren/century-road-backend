package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One "Perché conta" block, as written in src/main/resources/editorial/insights. Read from
 * JSON, validated once at startup by EditorialCatalog, immutable afterwards.
 *
 * The three texts are the structure the product asks for: what led up to the event, what
 * happened, what followed - the last one careful not to present everything that came after
 * as a direct effect.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Insight(
        String slug,
        String title,
        String summary,
        EventDate date,
        Place place,
        String before,
        String event,
        String after,
        List<InsightLink> links,
        List<Source> sources,
        List<String> notes,
        Provenance provenance) {

    public Insight {
        links = links == null ? List.of() : List.copyOf(links);
        sources = sources == null ? List.of() : List.copyOf(sources);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
