package centuryroad.history.exception;

/**
 * Wikipedia could not give an answer. The three subclasses are the only ways that can
 * happen, and everything that talks to Wikipedia reports failure through one of them: the
 * cache and the service decide what to do next (stale copy, other language) from the type
 * alone, without ever looking at an HTTP status or a socket error.
 */
public abstract class UpstreamException extends ApplicationException {

    protected UpstreamException(String errorCode, String message, String userMessage, Throwable cause) {
        super(errorCode, message, userMessage, cause);
    }
}
