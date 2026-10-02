package centuryroad.auth.exception;

import lombok.Getter;

/**
 * The base of every domain exception GlobalExceptionHandler knows how to turn
 * into a
 * response of its own, rather than a generic 500. errorCode is the stable,
 * machine-
 * readable string a client branches on; userMessage is the one a person reads.
 */
@Getter
public abstract class ApplicationException extends RuntimeException {

    private final String errorCode;
    private final String userMessage;

    protected ApplicationException(String errorCode, String message, String userMessage) {
        super(message);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }
}
