package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** A Wikipedia article linked from an entry's text. description and extract describe that
 *  article, not the event it was linked from. url is the attribution link CC BY-SA asks
 *  for, so a page without a usable one is dropped rather than shown without credit.
 *
 *  thumbnail and originalImage are the same picture at two sizes, both only ever from
 *  Commons; the original can be several megabytes, so its width and height are there for a
 *  frontend to decide whether to load it. coordinates is where the article's subject is, when
 *  it has a place. wikibaseItem is its Wikidata id (Q42), the same in every language. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A Wikipedia article linked from an item's text. Related reading, not the article about "
        + "the event: its description and extract describe the article, not what happened.")
public record PageRef(
        @Schema(description = "The article's title.", example = "Beirut") String title,
        @Schema(description = "Wikipedia's one-line description of the article. Absent when it has none.",
                example = "capital of Lebanon") String description,
        @Schema(description = "The opening paragraph of the article, as plain text. Absent when it has none.")
        String extract,
        @Schema(description = "The article on Wikipedia. Link to it whenever you show its text: the licence requires it.")
        String url,
        @Schema(description = "A small picture for lists. Absent when the article has none from Commons.")
        ImageRef thumbnail,
        @Schema(description = "The same picture at full size, for a detail view. Absent when there is no thumbnail.")
        ImageRef originalImage,
        @Schema(description = "Where the article's subject is. Absent for most pages: only places have one.")
        Coordinates coordinates,
        @Schema(description = "The Wikidata id, the same in every language: match a page across it and en with it.",
                example = "Q3820") String wikibaseItem) {
}
