package centuryroad.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * What a deployment gets unless somebody switches documentation on: no page, no document, and
 * no route to the services' own documents. The gateway is the one public address in production,
 * so a service's API description must not be reachable through it by accident.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsDisabledTest {

	private static final DisposableServer upstreamStub = HttpServer.create()
			.port(0)
			.route(routes -> routes.get("/**",
					(req, res) -> res.header("X-Upstream", "reached").sendString(Mono.just("stub"))))
			.bindNow();

	@LocalServerPort
	private int gatewayPort;

	@DynamicPropertySource
	static void routeToTheStub(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URI", () -> "http://localhost:" + upstreamStub.port());
		registry.add("HISTORY_SERVICE_URI", () -> "http://localhost:" + upstreamStub.port());
	}

	@AfterAll
	static void stopStub() {
		upstreamStub.disposeNow();
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort).build();
	}

	@Test
	void aServicesDocumentIsNotRelayed_andTheServiceIsNotEvenAsked() {
		client().get().uri("/docs/auth-service/v3/api-docs")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
				.expectHeader().doesNotExist("X-Upstream");
		client().get().uri("/docs/history-service/v3/api-docs")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
				.expectHeader().doesNotExist("X-Upstream");
	}

	@Test
	void noInteractivePageAndNoDocumentOfItsOwnIsServed() {
		client().get().uri("/swagger-ui.html").exchange().expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
		client().get().uri("/v3/api-docs").exchange().expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
		client().get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}
}
