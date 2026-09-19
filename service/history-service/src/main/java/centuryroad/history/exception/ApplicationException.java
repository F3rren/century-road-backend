package centuryroad.history.exception;

import lombok.Getter;

/**
 * The base of every exception GlobalExceptionHandler knows how to turn into a response of
 * its own, rather than a generic 500. errorCode is the stable, machine-readable string a
 * client branches on; userMessage is the one a person reads.
 */
@Getter
public abstract class ApplicationException extends RuntimeException {

    private final String errorCode;
    private final String userMessage;

    protected ApplicationException(String errorCode, String message, String userMessage) {
        this(errorCode, message, userMessage, null);
    }

    protected ApplicationException(String errorCode, String message, String userMessage, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }
}
