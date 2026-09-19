package centuryroad.history.exception;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place where errors become responses, all in the project's envelope.
 *
 * Extends ResponseEntityExceptionHandler, unlike auth-service's handler, so the protocol-level
 * cases Spring MVC raises itself - a month that is not a number, a missing parameter, a wrong
 * method, an unknown path - come through here too instead of falling back to Spring's own
 * error page. handleExceptionInternal is the one funnel they all go through.
 *
 * Nothing from Wikipedia - URL, status, body - is ever put in a response: the messages here
 * are ours, and the detail goes to the log.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private String sessionId() {
        return RequestCorrelationFilter.current();
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleInvalidRequest(InvalidRequestException ex) {
        return new ResponseEntity<>(
                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), sessionId()),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(UpstreamRateLimitedException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleRateLimited(UpstreamRateLimitedException ex) {
        // Round up: "Retry-After: 0" would invite an immediate retry into the cool-down.
        long seconds = Math.max(1, (ex.getRetryAfter().toMillis() + 999) / 1000);
        log.warn("Answering 503, Wikipedia is rate-limiting this service: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), sessionId()));
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleUnavailable(UpstreamUnavailableException ex) {
        log.warn("Answering 503, Wikipedia is unavailable and there is nothing to fall back on: {}", ex.getMessage());
        return new ResponseEntity<>(
                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), sessionId()),
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(UpstreamBadResponseException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleBadResponse(UpstreamBadResponseException ex) {
        log.error("Answering 502, Wikipedia's response was unusable: {}", ex.getMessage());
        return new ResponseEntity<>(
                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), sessionId()),
                HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled internal error", ex);
        return new ResponseEntity<>(
                ApiEnvelope.error("INTERNAL_ERROR", "Internal server error",
                        "Si e' verificato un errore imprevisto. Riprova piu' tardi.", sessionId()),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception ex, @Nullable Object body,
                                                             @NonNull HttpHeaders headers,
                                                             @NonNull HttpStatusCode statusCode,
                                                             @NonNull WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String code = status != null ? status.name() : "ERROR";
        String reason = status != null ? status.getReasonPhrase() : "Error";
        return new ResponseEntity<>(ApiEnvelope.error(code, reason, userMessageFor(statusCode), sessionId()),
                headers, statusCode);
    }

    private static String userMessageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "La richiesta non e' valida.";
            case 404 -> "Risorsa non trovata.";
            case 405 -> "Metodo non consentito per questa risorsa.";
            case 406, 415 -> "Formato non supportato.";
            default -> "La richiesta non puo' essere elaborata.";
        };
    }
}
