package centuryroad.history.exception;

/** Timeout, connection failure, a 5xx, or a call refused locally because the circuit is
 *  open or the concurrency limit is reached. Worth retrying, and worth serving a stale
 *  copy for. */
public class UpstreamUnavailableException extends UpstreamException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super("UPSTREAM_UNAVAILABLE", message,
                "Il servizio di Wikipedia non e' raggiungibile in questo momento. Riprova tra poco.", cause);
    }
}
