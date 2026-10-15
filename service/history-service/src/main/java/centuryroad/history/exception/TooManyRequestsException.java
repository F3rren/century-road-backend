package centuryroad.history.exception;

import lombok.Getter;

/**
 * Raised by ReportRateLimiter. Carries the delay to suggest, because only the limiter
 * knows it - the handler turns it into a Retry-After header.
 */
@Getter
public class TooManyRequestsException extends ApplicationException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, String userMessage, long retryAfterSeconds) {
        super("TOO_MANY_REQUESTS", message, userMessage);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
