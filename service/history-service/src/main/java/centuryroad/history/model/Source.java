package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** Where a statement of an insight can be checked. Always an https link. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A source an insight draws on, to be shown as a link.")
public record Source(
        @Schema(description = "What the link is, as it should be shown.", example = "Sputnik 1 - Wikipedia") String title,
        @Schema(description = "The page itself.", example = "https://en.wikipedia.org/wiki/Sputnik_1") String url,
        @Schema(description = "Who publishes it.", example = "Wikipedia") String publisher,
        @Schema(description = "The licence of the text, when it has one that asks for a credit.", example = "CC BY-SA 4.0") String license) {
}
