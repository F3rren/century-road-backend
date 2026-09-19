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
 * The gateway with the railway profile added to the active one, which is how it is switched on:
 * the images start with -Dspring.profiles.active=prod, which outranks SPRING_PROFILES_ACTIVE,
 * so the environment adds the profile with SPRING_PROFILES_INCLUDE=railway instead.
 *
 * On the VPS Caddy stands in front of the gateway and hides the actuator and adds the security
 * headers. Where nothing of ours does, the gateway has to do both itself.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.profiles.include=railway")
@ActiveProfiles("prod")
class RailwayProfileTest {

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
	void onlyHealthIsPublic() {
		client().get().uri("/actuator/health").exchange().expectStatus().isOk();
	}

	@Test
	void theMetricsAreNotExposed() {
		client().get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
		client().get().uri("/actuator/metrics").exchange().expectStatus().isNotFound();
	}

	@Test
	void theSecurityHeadersCaddyWouldHaveAddedAreThere() {
		client().get().uri("/api/auth/login").exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("Strict-Transport-Security", "max-age=300; includeSubDomains")
				.expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
				.expectHeader().valueEquals("X-Frame-Options", "DENY")
				.expectHeader().valueEquals("Referrer-Policy", "no-referrer");
	}

	@Test
	void theHeadersCaddyDidNotSetAreNotAdded() {
		// SecureHeaders adds these by default; the point is to match what the proxy did, and a
		// Content-Security-Policy written for pages means nothing on a JSON API.
		client().get().uri("/api/auth/login").exchange()
				.expectHeader().doesNotExist("Content-Security-Policy")
				.expectHeader().doesNotExist("X-Xss-Protection")
				.expectHeader().doesNotExist("X-Download-Options")
				.expectHeader().doesNotExist("X-Permitted-Cross-Domain-Policies");
	}

	@Test
	void noServerHeaderAdvertisesWhatIsRunning() {
		client().get().uri("/api/auth/login").exchange()
				.expectHeader().doesNotExist("Server");
	}

}
