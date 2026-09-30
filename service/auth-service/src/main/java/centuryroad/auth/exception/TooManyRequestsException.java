package centuryroad.auth.exception;

import lombok.Getter;

/**
 * Raised by LoginAttemptLimiter. Carries the delay to suggest, because only the
 * limiter knows it - the handler turns it into a Retry-After header.
 */
@Getter
public class TooManyRequestsException extends ApplicationException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, String userMessage, long retryAfterSeconds) {
        super("TOO_MANY_ATTEMPTS", message, userMessage);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
