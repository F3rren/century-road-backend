package centuryroad.history.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** An image hosted on Wikimedia Commons, plus the page that names its author and
 *  licence. Images from a local wiki are never turned into one of these - see
 *  WikimediaUrls for why. */
@Schema(description = "An image from Wikimedia Commons. Images from a single wiki are never included, because "
        + "nothing says whether they are free.")
public record ImageRef(
        @Schema(description = "The image file.") String url,
        @Schema(description = "Width in pixels. Originals can be very large: check before loading one.",
                example = "330") int width,
        @Schema(description = "Height in pixels.", example = "220") int height,
        @Schema(description = "The Commons page for this file, naming its author and licence. Show it next to the image.")
        String filePageUrl) {
}
