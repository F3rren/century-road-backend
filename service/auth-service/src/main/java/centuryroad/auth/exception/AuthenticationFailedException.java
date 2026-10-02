package centuryroad.auth.exception;

/**
 * Wrong email, wrong password, or a disabled account - deliberately
 * indistinguishable
 * to the caller, so a login attempt cannot be used to enumerate which emails
 * exist.
 */
public class AuthenticationFailedException extends ApplicationException {
    public AuthenticationFailedException(String message) {
        super("INVALID_CREDENTIALS", message, "Email o password non corretti.");
    }
}
