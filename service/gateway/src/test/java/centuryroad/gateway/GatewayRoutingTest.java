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
 * Stubs stand in for auth-service and history-service with reactor-netty (already on the classpath
 * transitively via spring-cloud-starter-gateway) so route resolution can be verified
 * without a real upstream. It is started as a static field initializer, not @BeforeAll,
 * so it is guaranteed listening before Spring resolves @DynamicPropertySource values
 * while building the gateway's ApplicationContext.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

	private static final DisposableServer authServiceStub = HttpServer.create()
			.port(0)
			.route(routes -> routes.get("/**",
					(req, res) -> res.header("X-Upstream", "auth-service")
							.header("X-Upstream-Path", req.uri())
							.sendString(Mono.just("auth-service-stub"))))
			.bindNow();

	private static final DisposableServer historyServiceStub = HttpServer.create()
			.port(0)
			.route(routes -> routes.get("/**",
					(req, res) -> res.header("X-Upstream", "history-service")
							.header("X-Upstream-Path", req.uri())
							.sendString(Mono.just("history-service-stub"))))
			.bindNow();

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
	void routesAuthPathToAuthService() {
		client().get().uri("/api/auth/login")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream", "auth-service");
	}

	@Test
	void routesAdminUsersPathToAuthService() {
		client().get().uri("/api/admin/users")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream", "auth-service");
	}

	@Test
	void routesMePathToAuthService() {
		client().get().uri("/api/me")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream", "auth-service");
	}

	@Test
	void unmatchedApiPathHasNoRoute() {
		client().get().uri("/api/events/123")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void anotherAdminPathIsNotForwardedJustBecauseItStartsWithApiAdmin() {
		// The predicate is /api/admin/users/**, not /api/admin/**: a future admin area
		// belonging to a different service must not be swallowed by this route.
		client().get().uri("/api/admin/settings")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void aPreflightFromTheConfiguredOriginIsAllowed() {
		// The gateway answers the preflight itself, so it never reaches the stub.
		client().options().uri("/api/auth/login")
				.header("Origin", "http://localhost:5173")
				.header("Access-Control-Request-Method", "POST")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
	}

	@Test
	void aPreflightFromAnyOtherOriginIsRefused() {
		// The property lists origins explicitly rather than "*", and this is the whole
		// point of that: a site nobody allowed cannot script calls against this API.
		client().options().uri("/api/auth/login")
				.header("Origin", "https://non-autorizzato.example")
				.header("Access-Control-Request-Method", "POST")
				.exchange()
				.expectStatus().isForbidden();
	}

	@Test
	void thePathReachesTheUpstreamUnmodified() {
		// auth-service serves its real /api/... paths, so the gateway deliberately has no
		// path-stripping filter - see the routes comment in application.properties.
		client().get().uri("/api/auth/login")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream-Path", "/api/auth/login");
	}

	@Test
	void routesHistoryPathToHistoryService() {
		client().get().uri("/api/history/on-this-day/10/16")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream", "history-service");
	}

	@Test
	void theHistoryPathAndItsQueryStringReachTheUpstreamUnmodified() {
		client().get().uri("/api/history/on-this-day/10/16?lang=en&types=events,births&year=-44")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Upstream-Path",
						"/api/history/on-this-day/10/16?lang=en&types=events,births&year=-44");
	}

	@Test
	void aPathThatOnlyStartsLikeTheHistoryOneIsNotForwarded() {
		// The predicate is /api/history/**, so /api/historyx must not be swallowed by it.
		client().get().uri("/api/historyx/anything")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void theTwoRoutesDoNotStealEachOthersPaths() {
		client().get().uri("/api/auth/login").exchange()
				.expectHeader().valueEquals("X-Upstream", "auth-service");
		client().get().uri("/api/history/on-this-day/1/1").exchange()
				.expectHeader().valueEquals("X-Upstream", "history-service");
	}

	@Test
	void aPreflightForTheHistoryRouteIsAnsweredByTheGatewayItself() {
		client().options().uri("/api/history/on-this-day/10/16")
				.header("Origin", "http://localhost:5173")
				.header("Access-Control-Request-Method", "GET")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
	}

}
