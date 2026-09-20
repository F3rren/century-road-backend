package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What a successful refresh returns.")
public record RefreshPayload(
        @Schema(description = "The new access token, a JWT. Send it as `Authorization: Bearer <token>`.")
        String token,
        @Schema(description = "The new refresh token. The one that was sent no longer works: keep this one.")
        String refreshToken,
        @Schema(description = "The scheme to use for the token in the Authorization header.", example = "Bearer")
        String tokenType) {

    public RefreshPayload(String token, String refreshToken) {
        this(token, refreshToken, "Bearer");
    }
}
