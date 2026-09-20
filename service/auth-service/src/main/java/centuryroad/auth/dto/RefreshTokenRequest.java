package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A refresh token, from a login or from a previous refresh.")
public record RefreshTokenRequest(
        @NotBlank(message = "Il refresh token e' obbligatorio.")
        @Schema(description = "The refresh token. Single-use: sending it uses it up.")
        String refreshToken) {
}
