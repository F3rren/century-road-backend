package centuryroad.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/**
 * The gateway serves one Swagger UI for every service. Each service publishes its own OpenAPI
 * document at /v3/api-docs, on a network the browser cannot reach in production, so the gateway
 * relays it: /docs/{service}/v3/api-docs is the service's /v3/api-docs. Stubs stand in for the
 * services, the way GatewayRoutingTest does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true" })
class OpenApiDocsTest {

	private static DisposableServer stub(String name) {
		return HttpServer.create()
				.port(0)
				.route(routes -> routes.get("/**",
						(req, res) -> res.header("X-Upstream", name)
								.header("X-Upstream-Path", req.uri())
								.header("Content-Type", "application/json")
								.sendString(Mono.just("{\"openapi\":\"3.0.1\"}"))))
				.bindNow();
	}

	// Static initializers, not @BeforeAll: they must be listening before Spring resolves
	// @DynamicPropertySource while it builds the context.
	private static final DisposableServer authServiceStub = stub("auth-service");
	private static final DisposableServer historyServiceStub = stub("history-service");

	@LocalServerPort
	private int gatewayPort;

	@DynamicPropertySource
	static void routeToStubs(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URI", () -> "http://localhost:" + authServiceStub.port());
		registry.add("HISTORY_SERVICE_URI", () -> "http://localhost:" + historyServiceStub.port());
	}

	@AfterAll
	static void stopStubs() {
		authServiceStub.disposeNow();
		historyServiceStub.disposeNow();
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort).build();
	}

	@Test
	void theAuthDocumentIsReadFromAuthServiceAtItsOwnPath() {
		client().get().uri("/docs/auth-service/v3/api-docs")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream", "auth-service")
				.expectHeader().valueEquals("X-Upstream-Path", "/v3/api-docs");
	}

	@Test
	void theHistoryDocumentIsReadFromHistoryServiceAtItsOwnPath() {
		client().get().uri("/docs/history-service/v3/api-docs")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream", "history-service")
				.expectHeader().valueEquals("X-Upstream-Path", "/v3/api-docs");
	}

	@Test
	void aDocumentRouteOnlyAnswersGet() {
		client().post().uri("/docs/auth-service/v3/api-docs")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void nothingElseUnderDocsIsRelayed() {
		client().get().uri("/docs/auth-service/actuator/prometheus")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
		client().get().uri("/docs/unknown-service/v3/api-docs")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void theInteractivePageListsBothServicesWithTheGatewaysOwnPathsToTheirDocuments() {
		client().get().uri("/v3/api-docs/swagger-config")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.urls[0].name").isEqualTo("auth-service")
				.jsonPath("$.urls[0].url").isEqualTo("/docs/auth-service/v3/api-docs")
				.jsonPath("$.urls[1].name").isEqualTo("history-service")
				.jsonPath("$.urls[1].url").isEqualTo("/docs/history-service/v3/api-docs");
	}

	@Test
	void theInteractivePageIsServed() {
		// /swagger-ui.html redirects to the page proper, so follow it like a browser does.
		WebTestClient browser = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.create().followRedirect(true)))
				.baseUrl("http://localhost:" + gatewayPort)
				.build();

		browser.get().uri("/swagger-ui.html")
				.accept(MediaType.TEXT_HTML)
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
				.expectBody(String.class).value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("swagger-ui"));
	}

	@Test
	void theApiRoutesAreUntouched() {
		client().get().uri("/api/auth/login")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream", "auth-service");
		client().get().uri("/api/history/on-this-day/10/16")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream", "history-service");
	}
}
