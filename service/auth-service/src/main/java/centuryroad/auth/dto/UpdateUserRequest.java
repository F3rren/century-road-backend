package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

/**
 * A request from an admin to update an existing user. password is optional: null or
 * blank means "leave it unchanged" - see AuthService.updateUser.
 */
@Schema(description = "The changes to make to a user. Only the fields sent are changed.")
public record UpdateUserRequest(
        @Email(message = "Il formato dell'email non e' valido.")
        @Schema(description = "A new email address. Must not belong to another user.",
                example = "mario.rossi@example.com")
        String email,
        @Schema(description = "A new password. Blank or missing leaves the current one.", format = "password",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        String password,
        @Pattern(regexp = "(?i)admin|user", message = "Il ruolo deve essere 'admin' o 'user'.")
        @Schema(description = "A new role. Not case-sensitive.", allowableValues = {"user", "admin"})
        String role,
        @Schema(description = "False disables the account: it can no longer log in.")
        Boolean enabled) {
}
