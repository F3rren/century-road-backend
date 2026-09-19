package centuryroad.history.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    private boolean success;
    private String error;
    private String message;
    private String userMessage;
    private T data;
    private String timestamp;
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
