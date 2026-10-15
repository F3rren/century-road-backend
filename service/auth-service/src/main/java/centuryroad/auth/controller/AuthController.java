package centuryroad.auth.controller;

import centuryroad.auth.config.RequestCorrelationFilter;
import centuryroad.auth.dto.ApiEnvelope;
import centuryroad.auth.dto.LoginPayload;
import centuryroad.auth.dto.LoginRequest;
import centuryroad.auth.dto.RefreshPayload;
import centuryroad.auth.dto.RefreshTokenRequest;
import centuryroad.auth.dto.UserSummaryDto;
import centuryroad.auth.exception.AuthenticationFailedException;
import centuryroad.auth.exception.InvalidRequestException;
import centuryroad.auth.exception.TooManyRequestsException;
import centuryroad.auth.model.User;
import centuryroad.auth.service.AuthService;
import centuryroad.auth.service.JwtService;
import centuryroad.auth.service.LoginAttemptLimiter;
import centuryroad.auth.service.RefreshTokenService;
import centuryroad.auth.service.UserService;
import centuryroad.auth.util.LogSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
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
 * to call: everyone else only ever verifies a token this one already issued.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Sign in, renew a token, sign out. The only endpoints reachable without a token.")
public class AuthController {

        private static final int MAX_EMAIL_LENGTH = 254;

        private final AuthService authService;
        private final UserService userService;
        private final JwtService jwtService;
        private final RefreshTokenService refreshTokenService;
        private final LoginAttemptLimiter loginAttemptLimiter;

        public AuthController(AuthService authService, UserService userService, JwtService jwtService,
                        RefreshTokenService refreshTokenService, LoginAttemptLimiter loginAttemptLimiter) {
                this.authService = authService;
                this.userService = userService;
                this.jwtService = jwtService;
                this.refreshTokenService = refreshTokenService;
                this.loginAttemptLimiter = loginAttemptLimiter;
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

                // Keyed on the caller's address. There are now two proxies in front of this
                // service - the TLS terminator and the gateway - so the connection's own remote
                // address is the gateway's, identical for everybody. What makes this the real
                // caller again is server.forward-headers-strategy in the prod profile, which
                // has Spring rewrite getRemoteAddr() from the Forwarded header the gateway
                // adds.
                // The gateway takes that address from X-Forwarded-For, which the terminator
                // overwrites rather than passing on what the caller sent, and it ignores a
                // Forwarded header from the caller (XForwardedOnlyHeaderTransformer), which the
                // terminator drops as well. So the caller cannot choose it.
                String limiterKey = httpRequest.getRemoteAddr() + "|" + request.email();
                long retryAfter = loginAttemptLimiter.checkAndRecord(limiterKey);
                if (retryAfter > 0) {
                        // The message reaches the response body, so it names neither the caller's
                        // address nor the email: the limiter key is the pair, never to be echoed.
                        throw new TooManyRequestsException("Too many login attempts",
                                        "Troppi tentativi. Riprova tra qualche minuto.", retryAfter);
                }

                User user;
                try {
                        user = authService.login(request.email(), request.password());
                } catch (AuthenticationFailedException e) {
                        // The address and the masked email, never the raw email: it is whatever the
                        // caller typed, and a log is archived and shared (see LogSanitizer).
                        log.warn("Login refused | {} | {}", httpRequest.getRemoteAddr(),
                                        LogSanitizer.maskEmail(request.email()));
                        throw e;
                }
                loginAttemptLimiter.reset(limiterKey);

                String token = jwtService.generateToken(user);
                String refreshToken = refreshTokenService.issue(user.getId());

                LoginPayload payload = new LoginPayload(token, refreshToken, UserSummaryDto.of(user));
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
                Long userId = refreshTokenService.rotate(request.refreshToken())
                                .orElseThrow(() -> new AuthenticationFailedException(
                                                "Refresh token not found, expired or already used"));

                // rotate() only proves the token was valid for this user id; the fields the new
                // access token's claims need (email, role) still have to come from the
                // database.
                User user = userService.findById(userId);
                // A disabled account must stop being able to renew its session: the login refuses
                // it, and a refresh token issued before it was disabled would otherwise keep
                // minting access tokens for as long as it keeps being rotated. Same exception and
                // text as an unknown token, so the answer does not say which it was.
                if (user == null || !user.isEnabled()) {
                        throw new AuthenticationFailedException("Refresh token not found, expired or already used");
                }

                String newToken = jwtService.generateToken(user);
                String newRefreshToken = refreshTokenService.issue(user.getId());

                return ResponseEntity.ok(ApiEnvelope.success("Token aggiornato",
                                new RefreshPayload(newToken, newRefreshToken), RequestCorrelationFilter.current()));
        }

        @Operation(summary = "Sign out by revoking a refresh token", description = """
                        Revokes the refresh token sent. The access token already issued is a signed JWT and cannot
                        be recalled: it stays valid until it expires.""")
        @ApiResponse(responseCode = "200", description = "Signed out.")
        @ApiResponse(responseCode = "400", description = "The refresh token is missing. `error` is VALIDATION_ERROR.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
        @PostMapping("/logout")
        public ResponseEntity<ApiEnvelope<Void>> logout(@Valid @RequestBody RefreshTokenRequest request) {
                refreshTokenService.revoke(request.refreshToken());
                return ResponseEntity.status(HttpStatus.OK)
                                .body(ApiEnvelope.success("Logout effettuato", null, RequestCorrelationFilter.current()));
        }
}
