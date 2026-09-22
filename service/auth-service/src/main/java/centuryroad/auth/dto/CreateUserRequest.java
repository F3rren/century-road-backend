package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A request from an admin to create a new user. @RequestBody, not @ModelAttribute: a
 * password must never sit in a URL, where it would reach access logs, browser history
 * and any Referer header sent afterwards - the same reasoning classroom-backend's
 * AdminUserController documents at the same two endpoints this was ported from.
 */
@Schema(description = "A new user, created by an administrator.")
public record CreateUserRequest(
        @NotBlank(message = "L'email e' obbligatoria.")
        @Email(message = "Il formato dell'email non e' valido.")
        @Schema(description = "The new account's email address. Must not be registered already.",
                example = "mario.rossi@example.com")
        String email,
        @NotBlank(message = "La password e' obbligatoria.")
        @Size(min = 8, message = "La password deve essere di almeno 8 caratteri.")
        @Schema(description = "The new account's password, at least 8 characters.", format = "password",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        String password,
        @Pattern(regexp = "(?i)admin|user", message = "Il ruolo deve essere 'admin' o 'user'.")
        @Schema(description = "The account's role. Not case-sensitive. `user` when omitted.",
                allowableValues = {"user", "admin"}, defaultValue = "user")
        String role) {
}
