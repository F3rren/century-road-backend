package centuryroad.auth.exception;

import centuryroad.auth.config.RequestCorrelationFilter;
import centuryroad.auth.dto.ApiEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/**
 * The single place where controller errors are turned into responses, in this
 * project's
 * envelope rather than Spring's default shape. Extends
 * ResponseEntityExceptionHandler, as
 * history-service's handler does, so the protocol-level cases Spring MVC raises
 * itself - a
 * body that is not JSON, an id that is not a number, a wrong method - answer
 * with their own
 * 4xx through handleExceptionInternal. Without it the catch-all below turned
 * every one of
 * them into a 500.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

        @ExceptionHandler(AuthenticationFailedException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleAuthenticationFailed(AuthenticationFailedException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), RequestCorrelationFilter.current()),
                                HttpStatus.UNAUTHORIZED);
        }

        @ExceptionHandler(InvalidRequestException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleInvalidRequest(InvalidRequestException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), RequestCorrelationFilter.current()),
                                HttpStatus.BAD_REQUEST);
        }

        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleNotFound(ResourceNotFoundException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), RequestCorrelationFilter.current()),
                                HttpStatus.NOT_FOUND);
        }

        @ExceptionHandler(DomainConflictException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleConflict(DomainConflictException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), RequestCorrelationFilter.current()),
                                HttpStatus.CONFLICT);
        }

        @ExceptionHandler(TooManyRequestsException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleTooManyRequests(TooManyRequestsException ex) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                                .body(ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(),
                                                RequestCorrelationFilter.current()));
        }

        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleAccessDenied(AccessDeniedException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error("ACCESS_DENIED", ex.getMessage(),
                                                "Non hai i permessi necessari per questa operazione.", RequestCorrelationFilter.current()),
                                HttpStatus.FORBIDDEN);
        }

        /** A @Valid body that is not valid, on an endpoint whose other arguments carry no constraint. */
        @Override
        protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                        @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
                return validationError(ex.getBindingResult().getFieldErrors().stream()
                                .map(FieldError::getDefaultMessage)
                                .collect(Collectors.joining(" ")));
        }

        /**
         * The same failure when the endpoint also has a constraint on an argument of its own
         * (the @Positive id of the admin endpoints): Spring then validates the body too as part
         * of method validation, and raises this instead.
         */
        @Override
        protected ResponseEntity<Object> handleHandlerMethodValidationException(
                        @NonNull HandlerMethodValidationException ex, @NonNull HttpHeaders headers,
                        @NonNull HttpStatusCode status, @NonNull WebRequest request) {
                return validationError(ex.getAllErrors().stream()
                                .map(MessageSourceResolvable::getDefaultMessage)
                                .collect(Collectors.joining(" ")));
        }

        private static ResponseEntity<Object> validationError(String userMessage) {
                return new ResponseEntity<>(
                                ApiEnvelope.error("VALIDATION_ERROR", "Validation failed", userMessage, RequestCorrelationFilter.current()),
                                HttpStatus.BAD_REQUEST);
        }

        @ExceptionHandler(DataIntegrityViolationException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
                // Not ex.getMessage(): the driver's own text for a unique-violation embeds the
                // conflicting value verbatim, and there is no single field to redact here -
                // see the equivalent handler in classroom-backend for the full reasoning.
                Throwable cause = ex.getMostSpecificCause();
                log.warn("Database constraint violated: {} ({})", ex.getClass().getSimpleName(),
                                cause.getClass().getSimpleName(), ex);
                return new ResponseEntity<>(
                                ApiEnvelope.error("CONFLICT", "Conflict with the current state of the data",
                                                "L'operazione non e' andata a buon fine per un conflitto con dati esistenti.",
                                                RequestCorrelationFilter.current()),
                                HttpStatus.CONFLICT);
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ApiEnvelope<Void>> handleGeneric(Exception ex) {
                log.error("Unhandled internal error", ex);
                return new ResponseEntity<>(
                                ApiEnvelope.error("INTERNAL_ERROR", "Internal server error",
                                                "Si e' verificato un errore imprevisto. Riprova piu' tardi.",
                                                RequestCorrelationFilter.current()),
                                HttpStatus.INTERNAL_SERVER_ERROR);
        }

        /** The one funnel every protocol-level case goes through: its own status, in the envelope. */
        @Override
        protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception ex, @Nullable Object body,
                        @NonNull HttpHeaders headers, @NonNull HttpStatusCode statusCode, @NonNull WebRequest request) {
                HttpStatus status = HttpStatus.resolve(statusCode.value());
                String code = status != null ? status.name() : "ERROR";
                String reason = status != null ? status.getReasonPhrase() : "Error";
                return new ResponseEntity<>(
                                ApiEnvelope.error(code, reason, userMessageFor(statusCode), RequestCorrelationFilter.current()),
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
