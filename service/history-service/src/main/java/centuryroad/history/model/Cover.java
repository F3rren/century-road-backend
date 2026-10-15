package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A path's cover. Only ever an image hosted on Wikimedia Commons, for the reason the rest of
 * the service gives (see WikimediaUrls): Commons takes only free files, and the file page
 * names the author and licence, which the frontend must show next to the image.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A path's cover image, from Wikimedia Commons.")
public record Cover(
        @Schema(description = "The image file, already at a size meant for a cover.") String imageUrl,
        @Schema(description = "The Commons page for the file, naming its author and licence. Show it next to the image.") String filePageUrl,
        @Schema(description = "A description of the picture for people who cannot see it.") String alt) {
}
