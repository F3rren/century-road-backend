package centuryroad.history.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * The generic response envelope every endpoint answers with: success, error, message,
 * userMessage, data, timestamp, sessionId. Fields left unset are absent from the JSON
 * rather than "null" (@JsonInclude below), so a success body carries no error/userMessage
 * keys and an error body carries no data key. Same shape auth-service uses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
public class ApiEnvelope<T> {

    @Schema(description = "True when the request was answered, false when it was refused or failed.")
    private boolean success;

    @Schema(description = "Machine-readable error code, such as INVALID_DATE. Only present on errors.",
            example = "INVALID_DATE")
    private String error;

    @Schema(description = "A message for developers and logs. Not meant to be shown to a user.")
    private String message;

    @Schema(description = "A message that is safe to show to a user, in Italian. Only present on errors.")
    private String userMessage;

    @Schema(description = "The answer itself. Only present on success.")
    private T data;

    @Schema(description = "When the answer was produced, ISO 8601 with offset.", example = "2026-09-20T10:22:12+02:00")
    private String timestamp;

    @Schema(description = "The request id, also sent as the X-Request-Id header. Quote it when reporting a problem.",
            example = "REQ_1A2B3C4D")
    private String sessionId;

    public static <T> ApiEnvelope<T> success(String message, T data, String sessionId) {
        ApiEnvelope<T> response = new ApiEnvelope<>();
        response.success = true;
        response.message = message;
        response.data = data;
        response.timestamp = OffsetDateTime.now().toString();
        response.sessionId = sessionId;
        return response;
    }

    public static <T> ApiEnvelope<T> error(String errorCode, String message, String userMessage, String sessionId) {
        ApiEnvelope<T> response = new ApiEnvelope<>();
        response.success = false;
        response.error = errorCode;
        response.message = message;
        response.userMessage = userMessage;
        response.timestamp = OffsetDateTime.now().toString();
        response.sessionId = sessionId;
        return response;
    }
}
