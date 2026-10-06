package centuryroad.history.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** One of the two or three events worth reading next, with why. */
@Schema(description = "Another insight worth reading after this one.")
public record InsightLink(
        @Schema(description = "The slug of the other insight.", example = "vostok-1-gagarin") String slug,
        @Schema(description = "Why it is worth reading next, in one sentence. A reason to read, not a claim of cause.") String reason) {
}
