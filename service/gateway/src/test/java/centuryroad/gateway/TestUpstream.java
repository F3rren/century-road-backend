package centuryroad.gateway;

import java.util.Map;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * A stand-in for auth-service that answers every GET with a plain 200, for the tests that are
 * about what the gateway does to a response and not about which route it took.
 */
final class TestUpstream {

	private TestUpstream() {
	}

	static DisposableServer start() {
		return startWithHeaders(Map.of());
	}

	/** The same, with response headers of its own: what a real service adds by itself. */
	static DisposableServer startWithHeaders(Map<String, String> headers) {
		return HttpServer.create()
				.port(0)
				.route(routes -> routes.get("/**", (req, res) -> {
					headers.forEach(res::header);
					return res.sendString(Mono.just("stub"));
				}))
				.bindNow();
	}

}
