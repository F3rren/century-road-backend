package centuryroad.auth.exception;

/**
 * An email already registered to somebody else - checked before the insert so
 * the
 * caller gets a clean 409 instead of a raw unique-constraint violation.
 */
public class DomainConflictException extends ApplicationException {
    public DomainConflictException(String message, String userMessage) {
        super("CONFLICT", message, userMessage);
    }
}
