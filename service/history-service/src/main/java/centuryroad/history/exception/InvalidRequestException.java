package centuryroad.history.exception;

/** The caller asked for something that cannot be answered. Each cause has a code of its own
 *  (INVALID_DATE, UNSUPPORTED_LANGUAGE, ...) so a frontend can tell them apart. */
public class InvalidRequestException extends ApplicationException {

    public InvalidRequestException(String errorCode, String message, String userMessage) {
        super(errorCode, message, userMessage);
    }
}
