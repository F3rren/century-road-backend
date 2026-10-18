package centuryroad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.netty.DisposableServer;

/**
 * The bounds the gateway puts on its upstreams, as configured: without them Reactor Netty
 * waits forever on a service that accepts the connection and then stalls. What they do when
 * hit is {@link GatewayResponseTimeoutTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTimeoutsTest {

	private static final DisposableServer upstream = TestUpstream.start();

	@Autowired
	private HttpClientProperties httpClient;

	@Autowired
	private RouteLocator routes;

	@DynamicPropertySource
	static void routeToStub(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URI", () -> "http://localhost:" + upstream.port());
		registry.add("HISTORY_SERVICE_URI", () -> "http://localhost:" + upstream.port());
		registry.add("FRONTEND_ORIGIN", () -> "http://localhost:5173");
	}

	@AfterAll
	static void stopStub() {
		upstream.disposeNow();
	}

	@Test
	void everyUpstreamCallIsBoundedByDefault() {
		assertThat(httpClient.getConnectTimeout()).isEqualTo(2000);
		assertThat(httpClient.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void historyServiceGetsMoreRoomThanTheDefault_becauseOneRequestCanLoadTwoLanguages() {
		List<Route> all = routes.getRoutes().collectList().block();

		Route history = all.stream().filter(r -> r.getId().equals("history-service")).findFirst().orElseThrow();
		Route auth = all.stream().filter(r -> r.getId().equals("auth-service")).findFirst().orElseThrow();

		long historyMillis = ((Number) history.getMetadata().get("response-timeout")).longValue();
		// More than the ~29s history-service needs in its worst case (see application-prod.yml),
		// and a bound all the same.
		assertThat(historyMillis).isBetween(29_000L, 60_000L);
		assertThat(auth.getMetadata()).doesNotContainKey("response-timeout");
	}
}
