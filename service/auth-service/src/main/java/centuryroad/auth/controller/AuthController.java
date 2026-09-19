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
 * Login, token refresh and logout - the only endpoints reachable without a token, and
 * the only three this service exposes that another Century Road service will never need
 * to call: everyone else only ever verifies a token this one already issued.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

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

    private String sessionId() {
        return RequestCorrelationFilter.current();
    }

    @PostMapping("/login")
    public ResponseEntity<ApiEnvelope<LoginPayload>> login(@RequestBody LoginRequest request,
                                                            HttpServletRequest httpRequest) {
        if (request.email() == null || request.email().isBlank()) {
            throw new InvalidRequestException("Missing email", "L'email e' obbligatoria.");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new InvalidRequestException("Missing password", "La password e' obbligatoria.");
        }

        // Keyed on the caller's address. There are now two proxies in front of this
        // service - the TLS terminator and the gateway - so the connection's own remote
        // address is the gateway's, identical for everybody. What makes this the real
        // caller again is server.forward-headers-strategy in the prod profile, which
        // has Spring rewrite getRemoteAddr() from the Forwarded header the gateway adds.
        // The gateway takes that address from X-Forwarded-For, which the terminator
        // overwrites rather than passing on what the caller sent, and it ignores a
        // Forwarded header from the caller (XForwardedOnlyHeaderTransformer), which the
        // terminator drops as well. So the caller cannot choose it.
        String limiterKey = httpRequest.getRemoteAddr() + "|" + request.email();
        long retryAfter = loginAttemptLimiter.checkAndRecord(limiterKey);
        if (retryAfter > 0) {
            throw new TooManyRequestsException("Too many login attempts for " + limiterKey,
                    "Troppi tentativi. Riprova tra qualche minuto.", retryAfter);
        }

        User user;
        try {
            user = authService.login(request.email(), request.password());
        } catch (AuthenticationFailedException e) {
            log.warn("Login refused | {}", limiterKey);
            throw e;
        }
        loginAttemptLimiter.reset(limiterKey);

        String token = jwtService.generateToken(user);
        String refreshToken = refreshTokenService.issue(user.getId());

        LoginPayload payload = new LoginPayload(token, refreshToken, UserSummaryDto.of(user));
        return ResponseEntity.ok(ApiEnvelope.success("Login effettuato con successo", payload, sessionId()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiEnvelope<RefreshPayload>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        Long userId = refreshTokenService.rotate(request.refreshToken())
                .orElseThrow(() -> new AuthenticationFailedException("Refresh token not found, expired or already used"));

        // rotate() only proves the token was valid for this user id; the fields the new
        // access token's claims need (email, role) still have to come from the database.
        User user = userService.findById(userId);
        if (user == null) {
            throw new AuthenticationFailedException("User no longer exists");
        }

        String newToken = jwtService.generateToken(user);
        String newRefreshToken = refreshTokenService.issue(user.getId());

        return ResponseEntity.ok(ApiEnvelope.success("Token aggiornato",
                new RefreshPayload(newToken, newRefreshToken), sessionId()));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiEnvelope<Void>> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiEnvelope.success("Logout effettuato", null, sessionId()));
    }
}
