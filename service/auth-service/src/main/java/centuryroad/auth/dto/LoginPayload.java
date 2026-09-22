package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The data half of a successful login, nested under ApiEnvelope.data. */
@Schema(description = "What a successful login returns.")
public record LoginPayload(
        @Schema(description = "The access token, a JWT. Send it as `Authorization: Bearer <token>`. Short-lived.")
        String token,
        @Schema(description = "A refresh token, to get a new access token when this one expires. Single-use.")
        String refreshToken,
        @Schema(description = "The account that logged in.") UserSummaryDto user,
        @Schema(description = "The scheme to use for the token in the Authorization header.", example = "Bearer")
        String tokenType) {

    public LoginPayload(String token, String refreshToken, UserSummaryDto user) {
        this(token, refreshToken, user, "Bearer");
    }
}
