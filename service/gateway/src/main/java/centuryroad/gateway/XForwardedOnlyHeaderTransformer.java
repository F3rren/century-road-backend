package centuryroad.gateway;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

/**
 * Trusts X-Forwarded-* and nothing else.
 *
 * Spring's own transformer reads the standard Forwarded header first and only falls back on
 * X-Forwarded-*. The reverse proxy in front of the gateway overwrites X-Forwarded-For but
 * leaves Forwarded exactly as the caller sent it, so a caller could put any address in it,
 * get the gateway to pass that address on as its own, and pick a new key for the login rate
 * limiter with every attempt.
 *
 * Dropping Forwarded here, before the default handling runs, leaves the proxy's headers as
 * the only ones that count, which is what the prod profile assumes.
 */
public class XForwardedOnlyHeaderTransformer extends ForwardedHeaderTransformer {

    private static final String FORWARDED = "Forwarded";

    @Override
    public ServerHttpRequest apply(ServerHttpRequest request) {
        return super.apply(request.mutate()
                .headers(headers -> headers.remove(FORWARDED))
                .build());
    }
}
