package centuryroad.auth.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request id has to reach three places at once - the response header, the
 * MDC and
 * current() - and has to be gone from the last two by the time the thread is
 * handed to
 * the next request, or a pooled thread would go on logging somebody else's id.
 */
class RequestCorrelationFilterUnitTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    private static final FilterChain NO_OP = (request, response) -> {
    };

    @Test
    void currentFallsBackToAFixedIdOutsideOfAnyRequest() {
        // Never null, so a caller wanting to log something needs no null check.
        assertThat(RequestCorrelationFilter.current()).isEqualTo("REQ_00000000");
    }

    @Test
    void theSameIdReachesTheHeaderTheMdcAndCurrent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] insideTheChain = new String[2];

        filter.doFilter(new MockHttpServletRequest(), response, (request, res) -> {
            insideTheChain[0] = RequestCorrelationFilter.current();
            insideTheChain[1] = MDC.get("requestId");
        });

        String header = response.getHeader(RequestCorrelationFilter.HEADER);
        assertThat(header).startsWith("REQ_").hasSize(12);
        assertThat(insideTheChain[0]).isEqualTo(header);
        assertThat(insideTheChain[1]).isEqualTo(header);
    }

    @Test
    void bothTheMdcAndTheThreadLocalAreClearedWhenTheRequestIsDone() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), NO_OP);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(RequestCorrelationFilter.current()).isEqualTo("REQ_00000000");
    }

    @Test
    void theyAreClearedEvenWhenTheChainThrows() {
        FilterChain throwing = (request, response) -> {
            throw new IOException("upstream blew up");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), throwing))
                .isInstanceOf(IOException.class);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(RequestCorrelationFilter.current()).isEqualTo("REQ_00000000");
    }

    @Test
    void twoRequestsNeverShareAnId() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, NO_OP);
        filter.doFilter(new MockHttpServletRequest(), second, NO_OP);

        assertThat(first.getHeader(RequestCorrelationFilter.HEADER))
                .isNotEqualTo(second.getHeader(RequestCorrelationFilter.HEADER));
    }
}
