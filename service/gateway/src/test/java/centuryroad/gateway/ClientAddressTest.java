package centuryroad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * What the gateway hands to auth-service as the caller's address, which is what the login rate
 * limiter there keys on: whoever can choose that address chooses a new key for every attempt
 * and is never limited. The gateway passes it on in a Forwarded header of its own
 * (for="address:port"), and that is what auth-service reads.
 *
 * The prod profile makes the gateway trust the forwarding headers it receives, which is safe
 * only if whatever sits in front of it overwrites them. The proxy overwrites X-Forwarded-For,
 * but the standard Forwarded header goes through it untouched, and Spring prefers it. So the
 * gateway has to ignore a Forwarded header from the caller itself and keep honouring
 * X-Forwarded-For.
 *
 * The strategy is set here on purpose rather than left to the default profile, so that this
 * test still means what it says if the profile ever changes.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "server.forward-headers-strategy=framework")
class ClientAddressTest {

	private static final String SEEN_X_FORWARDED_FOR = "X-Seen-X-Forwarded-For";
	private static final String SEEN_FORWARDED = "X-Seen-Forwarded";

	// Reports back, as response headers, the two headers the upstream received.
	private static final DisposableServer authServiceStub = HttpServer.create()
			.port(0)
			.route(routes -> routes.get("/**",
					(req, res) -> res
							.header(SEEN_X_FORWARDED_FOR, req.requestHeaders().get("X-Forwarded-For", "none"))
							.header(SEEN_FORWARDED, req.requestHeaders().get("Forwarded", "none"))
							.sendString(Mono.just("auth-service-stub"))))
			.bindNow();

	@LocalServerPort
	private int gatewayPort;

	@DynamicPropertySource
	static void routeToTheStub(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URI", () -> "http://localhost:" + authServiceStub.port());
	}

	@AfterAll
	static void stopStub() {
		authServiceStub.disposeNow();
	}

	private WebTestClient.ResponseSpec callWith(Consumer<HttpHeaders> headers) {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort).build()
				.get().uri("/api/auth/login")
				.headers(headers)
				.exchange()
				.expectStatus().isOk();
	}

	@Test
	void aForwardedHeaderFromTheCallerIsNotTrusted() {
		callWith(h -> h.set("Forwarded", "for=7.7.7.7"))
				.expectHeader().value(SEEN_X_FORWARDED_FOR, seen -> assertThat(seen).doesNotContain("7.7.7.7"))
				.expectHeader().value(SEEN_FORWARDED, seen -> assertThat(seen).doesNotContain("7.7.7.7"));
	}

	@Test
	void anXForwardedForFromTheProxyIsStillTheCallersAddress() {
		// What the proxy sets, and what the gateway has to keep honouring.
		callWith(h -> h.set("X-Forwarded-For", "9.9.9.9"))
				.expectHeader().value(SEEN_FORWARDED, seen -> assertThat(seen).contains("for=\"9.9.9.9"));
	}

	@Test
	void whenBothArriveOnlyXForwardedForCounts() {
		callWith(h -> {
			h.set("X-Forwarded-For", "9.9.9.9");
			h.set("Forwarded", "for=6.6.6.6");
		})
				.expectHeader().value(SEEN_X_FORWARDED_FOR, seen -> assertThat(seen).doesNotContain("6.6.6.6"))
				.expectHeader().value(SEEN_FORWARDED, seen -> assertThat(seen)
						.contains("for=\"9.9.9.9")
						.doesNotContain("6.6.6.6"));
	}

}
