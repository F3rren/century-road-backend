package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "The credentials of the account to sign in to.")
public record LoginRequest(
        @NotBlank(message = "L'email e' obbligatoria.")
        @Schema(description = "The account's email address.", example = "admin@example.com")
        String email,
        @NotBlank(message = "La password e' obbligatoria.")
        @Schema(description = "The account's password.", format = "password",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        String password) {
}
