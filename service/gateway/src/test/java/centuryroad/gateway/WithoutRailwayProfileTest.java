package centuryroad.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.DisposableServer;

/**
 * The gateway as the VPS runs it, with no railway profile: Prometheus scrapes the metrics over
 * the internal network on the same port as the API, and Caddy is the one adding headers. Nothing
 * the railway profile does may leak into this one, or the stack that works today changes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class WithoutRailwayProfileTest {

	private static final DisposableServer upstream = TestUpstream.start();

	@LocalServerPort
	private int gatewayPort;

	@DynamicPropertySource
	static void routeToTheStub(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URI", () -> "http://localhost:" + upstream.port());
	}

	@AfterAll
	static void stopStub() {
		upstream.disposeNow();
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort).build();
	}

	@Test
	void prometheusStillScrapesTheMetrics() {
		client().get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
	}

	@Test
	void theGatewayAddsNoSecurityHeadersOfItsOwn() {
		client().get().uri("/api/auth/login").exchange()
				.expectStatus().isOk()
				.expectHeader().doesNotExist("Strict-Transport-Security")
				.expectHeader().doesNotExist("X-Frame-Options")
				.expectHeader().doesNotExist("X-Content-Type-Options");
	}

}
