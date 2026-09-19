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
 * HSTS starts at five minutes on purpose, so that a broken certificate in the first rollout
 * cannot lock browsers out for long, and HSTS_MAX_AGE is what raises it once HTTPS has been
 * stable. The same variable the proxy reads, so there is one knob whichever way it is deployed.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.profiles.include=railway", "HSTS_MAX_AGE=31536000" })
@ActiveProfiles("prod")
class RailwayProfileHstsTest {

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

	@Test
	void hstsMaxAgeFollowsTheVariable() {
		WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort).build()
				.get().uri("/api/auth/login").exchange()
				.expectHeader().valueEquals("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
	}

}
