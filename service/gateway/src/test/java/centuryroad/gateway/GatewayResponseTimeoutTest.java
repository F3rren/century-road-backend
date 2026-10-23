package centuryroad.gateway;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * What a stalled upstream becomes at the gateway: a 504, not a request hanging open. The
 * default is shrunk to 300ms so the test does not wait ten seconds, and both stubs take
 * longer than that - which is exactly what shows the history route's own limit (set in the
 * route definition, not by this property) is the one that applies to it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.cloud.gateway.httpclient.response-timeout=300ms")
class GatewayResponseTimeoutTest {

	private static final DisposableServer authServiceStub = slowStub("auth-service");

	private static final DisposableServer historyServiceStub = slowStub("history-service");

	private static DisposableServer slowStub(String name) {
		return HttpServer.create()
				.port(0)
				.route(routes -> routes.get("/**",
						(req, res) -> res.sendString(Mono.just(name + "-stub").delayElement(Duration.ofMillis(1200)))))
				.bindNow();
	}

	@LocalServerPort
	private int gatewayPort;

	@DynamicPropertySource
	static void routeToStubs(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URI", () -> "http://localhost:" + authServiceStub.port());
		registry.add("HISTORY_SERVICE_URI", () -> "http://localhost:" + historyServiceStub.port());
		registry.add("FRONTEND_ORIGIN", () -> "http://localhost:5173");
	}

	@AfterAll
	static void stopStubs() {
		authServiceStub.disposeNow();
		historyServiceStub.disposeNow();
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort)
				.responseTimeout(Duration.ofSeconds(10)).build();
	}

	@Test
	void anUpstreamThatStallsPastTheDefaultBecomesA504() {
		client().get().uri("/api/auth/login")
				.exchange()
				.expectStatus().isEqualTo(504);
	}

	@Test
	void theHistoryRouteKeepsWaiting_itsOwnLimitIsLongerThanTheDefault() {
		client().get().uri("/api/history/on-this-day/10/16")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class).isEqualTo("history-service-stub");
	}
}
