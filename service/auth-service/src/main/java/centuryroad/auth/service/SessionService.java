package centuryroad.auth.service;

import centuryroad.auth.exception.AuthenticationFailedException;
import centuryroad.auth.exception.TooManyRequestsException;
import centuryroad.auth.model.User;
import centuryroad.auth.util.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * A session's life: opening one with a password, renewing it with a refresh token, closing
 * it. The rules around them live here - how many attempts a caller gets, who may renew, the
 * one place a token pair is issued - so AuthController is left with the HTTP side only.
 */
@Slf4j
@Service
public class SessionService {

    /** An access token, the refresh token to renew it with, and the user they were issued to. */
    public record Tokens(String accessToken, String refreshToken, User user) {
    }

    private final AuthService authService;
    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptLimiter loginAttemptLimiter;

    public SessionService(AuthService authService, UserService userService, JwtService jwtService,
            RefreshTokenService refreshTokenService, LoginAttemptLimiter loginAttemptLimiter) {
        this.authService = authService;
        this.userService = userService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    /**
     * clientAddress is the caller's, as the request reports it. There are two proxies in
     * front of this service - the TLS terminator and the gateway - so the connection's own
     * remote address is the gateway's, identical for everybody. What makes it the real caller
     * again is server.forward-headers-strategy in the prod profile, which has Spring rewrite
     * getRemoteAddr() from the Forwarded header the gateway adds. The gateway takes that
     * address from X-Forwarded-For, which the terminator overwrites rather than passing on
     * what the caller sent, and it ignores a Forwarded header from the caller
     * (XForwardedOnlyHeaderTransformer), which the terminator drops as well. So the caller
     * cannot choose it.
     */
    public Tokens login(String email, String password, String clientAddress) {
        String limiterKey = clientAddress + "|" + email;
        long retryAfter = loginAttemptLimiter.checkAndRecord(limiterKey);
        if (retryAfter > 0) {
            // The message reaches the response body, so it names neither the caller's
            // address nor the email: the limiter key is the pair, never to be echoed.
            throw new TooManyRequestsException("Too many login attempts",
                    "Troppi tentativi. Riprova tra qualche minuto.", retryAfter);
        }

        User user;
        try {
            user = authService.login(email, password);
        } catch (AuthenticationFailedException e) {
            // The address and the masked email, never the raw email: it is whatever the
            // caller typed, and a log is archived and shared (see LogSanitizer).
            log.warn("Login refused | {} | {}", clientAddress, LogSanitizer.maskEmail(email));
            throw e;
        }
        loginAttemptLimiter.reset(limiterKey);
        return issue(user);
    }

    /** Refresh tokens are single-use: the one sent stops working, and a new one comes back. */
    public Tokens refresh(String refreshToken) {
        Long userId = refreshTokenService.rotate(refreshToken)
                .orElseThrow(() -> new AuthenticationFailedException(
                        "Refresh token not found, expired or already used"));

        // rotate() only proves the token was valid for this user id; the fields the new
        // access token's claims need (email, role) still have to come from the database.
        User user = userService.findById(userId);
        // A disabled account must stop being able to renew its session: the login refuses
        // it, and a refresh token issued before it was disabled would otherwise keep
        // minting access tokens for as long as it keeps being rotated. Same exception and
        // text as an unknown token, so the answer does not say which it was.
        if (user == null || !user.isEnabled()) {
            throw new AuthenticationFailedException("Refresh token not found, expired or already used");
        }
        return issue(user);
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private Tokens issue(User user) {
        return new Tokens(jwtService.generateToken(user), refreshTokenService.issue(user.getId()), user);
    }
}
