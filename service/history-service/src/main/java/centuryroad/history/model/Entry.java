package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One line of a section. text is the event itself. pages are the articles linked from it,
 *  in the order they appear in the text: related reading, not "the article about the
 *  event", which most events do not have. year is null for holidays, and negative for
 *  years before the common era. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One item of a section: an event, a birth, a death, a holiday or a selected entry.")
public record Entry(
        @Schema(description = "What happened. This is the headline: never use the first page in its place.")
        String text,
        @Schema(description = "The year. Negative before the common era (-44 is 44 BC). Absent for holidays.",
                example = "1978") Integer year,
        @Schema(description = "The Wikipedia articles linked from the text, in the order they appear in it. "
                + "Related reading, not the article about the event; may be empty.") List<PageRef> pages) {

    public Entry {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }
}
