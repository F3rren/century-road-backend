package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import centuryroad.auth.model.User;

@Schema(description = "The user that was just created.")
public record UserRegisterAck(
        @Schema(description = "The new user's id.", example = "42") Long id,
        @Schema(description = "The new user's email address.", example = "mario.rossi@example.com") String email,
        @Schema(description = "The new user's role.", allowableValues = {"ADMIN", "USER"}, example = "USER") String role) {

    public UserRegisterAck(User user) {
        this(user.getId(), user.getEmail(), user.getRole().name());
    }
}
