package centuryroad.history.exception;

/**
 * The caller named something that does not exist: a path or an insight that has no such slug.
 * Each kind has its own code (PATH_NOT_FOUND, INSIGHT_NOT_FOUND) so a frontend can tell them apart.
 */
public class NotFoundException extends ApplicationException {

    public NotFoundException(String errorCode, String message, String userMessage) {
        super(errorCode, message, userMessage);
    }
}
