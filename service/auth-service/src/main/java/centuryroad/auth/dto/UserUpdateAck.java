package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import centuryroad.auth.model.User;

@Schema(description = "The user as it is after the update.")
public record UserUpdateAck(
        @Schema(description = "The user's id.", example = "42") Long id,
        @Schema(description = "The user's email address.", example = "mario.rossi@example.com") String email,
        @Schema(description = "The user's role.", allowableValues = {"ADMIN", "USER"}, example = "USER") String role,
        @Schema(description = "False when the account is disabled: it cannot log in.") boolean enabled) {

    public UserUpdateAck(User user) {
        this(user.getId(), user.getEmail(), user.getRole().name(), user.isEnabled());
    }
}
