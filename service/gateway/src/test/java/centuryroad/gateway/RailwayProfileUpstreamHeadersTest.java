package centuryroad.gateway;

import java.util.Map;

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
 * A service adds security headers of its own: auth-service runs Spring Security, which sends
 * HSTS with a one-year max-age as soon as it sees a request it takes for secure. Behind the
 * proxy that never reached a browser, because the proxy replaced the header. With nothing of
 * ours in front, the gateway has to do the replacing, or HSTS_MAX_AGE is not the value a
 * browser gets for the routes that matter most, the ones people log in through.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.profiles.include=railway")
@ActiveProfiles("prod")
class RailwayProfileUpstreamHeadersTest {

	private static final DisposableServer upstream = TestUpstream.startWithHeaders(Map.of(
			"Strict-Transport-Security", "max-age=31536000 ; includeSubDomains",
			"X-Frame-Options", "SAMEORIGIN",
			"X-Content-Type-Options", "not-what-the-gateway-says",
			"Referrer-Policy", "origin"));

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
	void theGatewaysHeadersReplaceTheOnesAServiceSetsItself() {
		// valueEquals compares the whole list of values, so a second copy of a header, the
		// service's next to the gateway's, would fail it as well.
		WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort).build()
				.get().uri("/api/auth/login").exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("Strict-Transport-Security", "max-age=300; includeSubDomains")
				.expectHeader().valueEquals("X-Frame-Options", "DENY")
				.expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
				.expectHeader().valueEquals("Referrer-Policy", "no-referrer");
	}

}
