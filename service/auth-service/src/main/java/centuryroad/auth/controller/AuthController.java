package centuryroad.auth.controller;

import centuryroad.auth.config.RequestCorrelationFilter;
import centuryroad.auth.dto.ApiEnvelope;
import centuryroad.auth.dto.LoginPayload;
import centuryroad.auth.dto.LoginRequest;
import centuryroad.auth.dto.RefreshPayload;
import centuryroad.auth.dto.RefreshTokenRequest;
import centuryroad.auth.dto.UserSummaryDto;
import centuryroad.auth.exception.InvalidRequestException;
import centuryroad.auth.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login, token refresh and logout - the only endpoints reachable without a
 * token, and
 * the only three this service exposes that another Century Road service will
 * never need
 * to call: everyone else only ever verifies a token this one already issued. The rules
 * behind them are SessionService's; this class is their HTTP side.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Sign in, renew a token, sign out. The only endpoints reachable without a token.")
public class AuthController {

        private static final int MAX_EMAIL_LENGTH = 254;

        private final SessionService sessionService;

        public AuthController(SessionService sessionService) {
                this.sessionService = sessionService;
        }

        @Operation(summary = "Log in with email and password", description = """
                        Returns an access token, a JWT to send afterwards as `Authorization: Bearer <token>`, and
                        a refresh token to renew it with.

                        Attempts are limited per caller and email: too many is a 429 with a `Retry-After` header.
                        A wrong email, a wrong password and a disabled account all answer the same 401, on purpose,
                        so the answer does not say which accounts exist.""")
        @ApiResponse(responseCode = "200", description = "Logged in.")
        @ApiResponse(responseCode = "400", description = "Email or password missing. `error` is INVALID_REQUEST.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
        @ApiResponse(responseCode = "401", description = "Email or password not correct, or the account is disabled. "
                        + "`error` is INVALID_CREDENTIALS.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
        @ApiResponse(responseCode = "429", description = "Too many attempts. `error` is TOO_MANY_ATTEMPTS.", headers = @Header(name = "Retry-After", description = "Seconds to wait before trying again.", schema = @Schema(type = "integer")), content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
        @PostMapping("/login")
        public ResponseEntity<ApiEnvelope<LoginPayload>> login(@RequestBody LoginRequest request,
                        HttpServletRequest httpRequest) {
                if (request.email() == null || request.email().isBlank()) {
                        throw new InvalidRequestException("Missing email", "L'email e' obbligatoria.");
                }
                if (request.password() == null || request.password().isBlank()) {
                        throw new InvalidRequestException("Missing password", "La password e' obbligatoria.");
                }
                // 254 is the longest an address can be. Past it the value is no email, and it is
                // part of the limiter's key, so it is not kept around for a minute to prove it.
                if (request.email().length() > MAX_EMAIL_LENGTH) {
                        throw new InvalidRequestException("Email too long", "L'email non e' valida.");
                }

                SessionService.Tokens tokens = sessionService.login(request.email(), request.password(),
                                httpRequest.getRemoteAddr());

                LoginPayload payload = new LoginPayload(tokens.accessToken(), tokens.refreshToken(),
                                UserSummaryDto.of(tokens.user()));
                return ResponseEntity.ok(ApiEnvelope.success("Login effettuato con successo", payload, RequestCorrelationFilter.current()));
        }

        @Operation(summary = "Exchange a refresh token for a new access token", description = """
                        Refresh tokens are single-use: the answer carries a new one, and the one just sent stops
                        working. Keep the new one for next time.""")
        @ApiResponse(responseCode = "200", description = "A new access token and a new refresh token.")
        @ApiResponse(responseCode = "400", description = "The refresh token is missing. `error` is VALIDATION_ERROR.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
        @ApiResponse(responseCode = "401", description = "The refresh token is unknown, expired or already used, or "
                        + "its user no longer exists. `error` is INVALID_CREDENTIALS.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
        @PostMapping("/refresh")
        public ResponseEntity<ApiEnvelope<RefreshPayload>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
                SessionService.Tokens tokens = sessionService.refresh(request.refreshToken());

                return ResponseEntity.ok(ApiEnvelope.success("Token aggiornato",
                                new RefreshPayload(tokens.accessToken(), tokens.refreshToken()),
                                RequestCorrelationFilter.current()));
        }

        @Operation(summary = "Sign out by revoking a refresh token", description = """
                        Revokes the refresh token sent. The access token already issued is a signed JWT and cannot
                        be recalled: it stays valid until it expires.""")
        @ApiResponse(responseCode = "200", description = "Signed out.")
        @ApiResponse(responseCode = "400", description = "The refresh token is missing. `error` is VALIDATION_ERROR.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
        @PostMapping("/logout")
        public ResponseEntity<ApiEnvelope<Void>> logout(@Valid @RequestBody RefreshTokenRequest request) {
                sessionService.logout(request.refreshToken());
                return ResponseEntity.status(HttpStatus.OK)
                                .body(ApiEnvelope.success("Logout effettuato", null, RequestCorrelationFilter.current()));
        }
}
