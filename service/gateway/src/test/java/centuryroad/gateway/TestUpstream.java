package centuryroad.gateway;

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
		return HttpServer.create()
				.port(0)
				.route(routes -> routes.get("/**", (req, res) -> res.sendString(Mono.just("stub"))))
				.bindNow();
	}

}
