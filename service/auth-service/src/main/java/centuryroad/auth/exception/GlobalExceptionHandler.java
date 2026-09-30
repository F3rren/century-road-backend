package centuryroad.auth.exception;

import centuryroad.auth.config.RequestCorrelationFilter;
import centuryroad.auth.dto.ApiEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * The single place where controller errors are turned into responses, in this
 * project's
 * envelope rather than Spring's default shape. Deliberately NOT extending
 * ResponseEntityExceptionHandler (unlike the classroom-backend project this was
 * ported
 * from) to keep this first pass small: malformed-request cases (bad JSON, wrong
 * path
 * variable type) fall back to Spring's own error page for now, a known
 * simplification to
 * revisit once this service has to face the same protocol-level edge cases.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

        private String sessionId() {
                return RequestCorrelationFilter.current();
        }

        @ExceptionHandler(AuthenticationFailedException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleAuthenticationFailed(AuthenticationFailedException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), sessionId()),
                                HttpStatus.UNAUTHORIZED);
        }

        @ExceptionHandler(InvalidRequestException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleInvalidRequest(InvalidRequestException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), sessionId()),
                                HttpStatus.BAD_REQUEST);
        }

        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleNotFound(ResourceNotFoundException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), sessionId()),
                                HttpStatus.NOT_FOUND);
        }

        @ExceptionHandler(DomainConflictException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleConflict(DomainConflictException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(), sessionId()),
                                HttpStatus.CONFLICT);
        }

        @ExceptionHandler(TooManyRequestsException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleTooManyRequests(TooManyRequestsException ex) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                                .body(ApiEnvelope.error(ex.getErrorCode(), ex.getMessage(), ex.getUserMessage(),
                                                sessionId()));
        }

        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleAccessDenied(AccessDeniedException ex) {
                return new ResponseEntity<>(
                                ApiEnvelope.error("ACCESS_DENIED", ex.getMessage(),
                                                "Non hai i permessi necessari per questa operazione.", sessionId()),
                                HttpStatus.FORBIDDEN);
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ApiEnvelope<Void>> handleValidation(MethodArgumentNotValidException ex) {
                String userMessage = ex.getBindingResult().getFieldErrors().stream()
                                .map(FieldError::getDefaultMessage)
                                .collect(Collectors.joining(" "));
                return new ResponseEntity<>(
                                ApiEnvelope.error("VALIDATION_ERROR", "Validation failed", userMessage, sessionId()),
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
                                                sessionId()),
                                HttpStatus.CONFLICT);
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ApiEnvelope<Void>> handleGeneric(Exception ex) {
                log.error("Unhandled internal error", ex);
                return new ResponseEntity<>(
                                ApiEnvelope.error("INTERNAL_ERROR", "Internal server error",
                                                "Si e' verificato un errore imprevisto. Riprova piu' tardi.",
                                                sessionId()),
                                HttpStatus.INTERNAL_SERVER_ERROR);
        }
}
