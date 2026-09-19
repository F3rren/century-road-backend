package centuryroad.history.exception;

import lombok.Getter;

import java.time.Duration;

/** Wikipedia answered 429, or we are still inside the cool-down that answer started.
 *  retryAfter is how long to stay away, which the handler passes on as Retry-After. */
@Getter
public class UpstreamRateLimitedException extends UpstreamException {

    private final Duration retryAfter;

    public UpstreamRateLimitedException(Duration retryAfter) {
        super("UPSTREAM_RATE_LIMITED", "Wikipedia asked us to slow down for " + retryAfter.toSeconds() + "s",
                "Troppe richieste verso Wikipedia in questo momento. Riprova tra poco.", null);
        this.retryAfter = retryAfter;
    }
}
