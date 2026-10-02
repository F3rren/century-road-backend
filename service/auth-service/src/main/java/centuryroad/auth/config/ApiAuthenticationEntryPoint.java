package centuryroad.auth.config;

import centuryroad.auth.dto.ApiEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * What a protected route answers when no valid token was presented - in this
 * project's own envelope, not Spring Security's default WWW-Authenticate-only
 * 401.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiEnvelope<Void> body = ApiEnvelope.error("UNAUTHENTICATED", "Authentication required",
                "Devi effettuare l'accesso per continuare.", RequestCorrelationFilter.current());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
