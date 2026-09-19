package centuryroad.history.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * Mints a request id and makes it available two ways: in the MDC, so every log line for this
 * request carries it, and on the response header, so a client and a person reading logs can
 * correlate the same request. GlobalExceptionHandler reads the same id via current(), which
 * is what lets an error response and its log line share one value. Same filter, same header
 * and same id format as auth-service, so a request can be followed across services.
 */
@Component
@Order(1)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** The id of the request being served on this thread, or a fallback if called outside
     *  one - never null, so callers never need a null check just to log something. */
    public static String current() {
        String id = CURRENT.get();
        return id != null ? id : "REQ_00000000";
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String id = "REQ_" + randomHex(8);
        CURRENT.set(id);
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            CURRENT.remove();
        }
    }

    private static String randomHex(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = HEX[RANDOM.nextInt(HEX.length)];
        }
        return new String(out);
    }
}
