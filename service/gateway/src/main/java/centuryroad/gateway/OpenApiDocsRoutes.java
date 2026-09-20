package centuryroad.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.List;

/**
 * Relays each service's OpenAPI document, so the Swagger UI served here can read them.
 * /docs/{service}/v3/api-docs is that service's own /v3/api-docs: in production the services
 * are on a network a browser cannot reach, and the gateway is the one address it can.
 *
 * Only exists while documentation is switched on (springdoc.api-docs.enabled). Otherwise there
 * is no such route, and a service's API description cannot be reached through the public
 * address by accident. Only GET, and only that one path per service: nothing else of a
 * service, its actuator for instance, is made reachable this way.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiDocsRoutes {

	/** Ids of the routes in application.properties that lead to each service. The document is
	 *  fetched from the same address as the API, so a change of AUTH_SERVICE_URI or
	 *  HISTORY_SERVICE_URI moves both. */
	private static final List<String> SERVICES = List.of("auth-service", "history-service");

	@Bean
	RouteLocator openApiDocsRouteLocator(RouteLocatorBuilder builder, GatewayProperties gateway) {
		RouteLocatorBuilder.Builder routes = builder.routes();
		for (String service : SERVICES) {
			URI upstream = upstreamOf(service, gateway);
			routes.route(service + "-docs", route -> route
					.method(HttpMethod.GET).and().path("/docs/" + service + "/v3/api-docs")
					.filters(filters -> filters.setPath("/v3/api-docs"))
					.uri(upstream));
		}
		return routes.build();
	}

	private static URI upstreamOf(String routeId, GatewayProperties gateway) {
		return gateway.getRoutes().stream()
				.filter(route -> routeId.equals(route.getId()))
				.map(RouteDefinition::getUri)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No route '" + routeId
						+ "' in spring.cloud.gateway.routes, so there is nowhere to relay its OpenAPI document from"));
	}
}
