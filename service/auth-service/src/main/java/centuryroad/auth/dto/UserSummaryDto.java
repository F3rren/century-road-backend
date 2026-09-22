package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import centuryroad.auth.model.User;

/**
 * What a user looks like from the outside - never the password. A static factory rather
 * than a Jackson-serialised entity, so adding a field to User later requires a deliberate
 * choice here too instead of it appearing in a response by accident.
 */
@Schema(description = "A user as seen from outside. Never carries the password.")
public record UserSummaryDto(
        @Schema(description = "The user's id.", example = "42") Long id,
        @Schema(description = "The user's email address.", example = "mario.rossi@example.com") String email,
        @Schema(description = "The user's role.", allowableValues = {"ADMIN", "USER"}, example = "USER") String role,
        @Schema(description = "False when an administrator has disabled the account: it cannot log in.")
        boolean enabled,
        @Schema(description = "When the account was created, ISO 8601 with offset.",
                example = "2026-09-18T20:47:20+02:00") String createdAt) {

    public static UserSummaryDto of(User user) {
        return new UserSummaryDto(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.isEnabled(),
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
    }
}
