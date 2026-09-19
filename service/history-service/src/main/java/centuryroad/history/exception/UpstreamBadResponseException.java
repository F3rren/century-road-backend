package centuryroad.history.exception;

/** Wikipedia answered, but not with anything this service understands: a 4xx for a request
 *  that was validated beforehand (so the API itself has changed), a body that is not JSON,
 *  or a body over the size limit. Not retried - asking again gets the same answer - and
 *  worth an alert, because it means the integration is broken rather than the network. */
public class UpstreamBadResponseException extends UpstreamException {

    public UpstreamBadResponseException(String message) {
        this(message, null);
    }

    public UpstreamBadResponseException(String message, Throwable cause) {
        super("UPSTREAM_BAD_RESPONSE", message,
                "Wikipedia ha risposto in un formato inatteso. Riprova piu' tardi.", cause);
    }
}
