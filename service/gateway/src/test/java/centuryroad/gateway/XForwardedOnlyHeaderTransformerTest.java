package centuryroad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

class XForwardedOnlyHeaderTransformerTest {

	private static final String PROXY_ADDRESS = "10.0.0.1";

	private final ForwardedHeaderTransformer transformer = new XForwardedOnlyHeaderTransformer();

	private static MockServerHttpRequest.BaseBuilder<?> callerThroughTheProxy() {
		return MockServerHttpRequest.get("http://gateway.internal/api/auth/login")
				.remoteAddress(new InetSocketAddress(PROXY_ADDRESS, 40000));
	}

	// getHostString and not getAddress: the address taken from a forwarding header is not
	// resolved, so getAddress() is null for it.
	private static String remoteHostOf(ServerHttpRequest request) {
		return request.getRemoteAddress().getHostString();
	}

	@Test
	void aForwardedHeaderIsIgnoredAndDropped() {
		ServerHttpRequest result = transformer.apply(
				callerThroughTheProxy().header("Forwarded", "for=7.7.7.7").build());

		assertThat(remoteHostOf(result)).isEqualTo(PROXY_ADDRESS);
		assertThat(result.getHeaders()).doesNotContainKey("Forwarded");
	}

	@Test
	void xForwardedForStillSetsTheRemoteAddress() {
		ServerHttpRequest result = transformer.apply(
				callerThroughTheProxy().header("X-Forwarded-For", "9.9.9.9").build());

		assertThat(remoteHostOf(result)).isEqualTo("9.9.9.9");
	}

	@Test
	void xForwardedForWinsWhenBothArrive() {
		ServerHttpRequest result = transformer.apply(callerThroughTheProxy()
				.header("X-Forwarded-For", "9.9.9.9")
				.header("Forwarded", "for=6.6.6.6")
				.build());

		assertThat(remoteHostOf(result)).isEqualTo("9.9.9.9");
		assertThat(result.getHeaders()).doesNotContainKey("Forwarded");
	}

	@Test
	void theSchemeAndHostFromTheProxyAreStillHonoured() {
		ServerHttpRequest result = transformer.apply(callerThroughTheProxy()
				.header("X-Forwarded-Proto", "https")
				.header("X-Forwarded-Host", "api.example.test")
				.build());

		assertThat(result.getURI().getScheme()).isEqualTo("https");
		assertThat(result.getURI().getHost()).isEqualTo("api.example.test");
	}

	@Test
	void aRequestWithoutForwardingHeadersIsLeftAlone() {
		ServerHttpRequest result = transformer.apply(callerThroughTheProxy().build());

		assertThat(remoteHostOf(result)).isEqualTo(PROXY_ADDRESS);
		assertThat(result.getURI().getHost()).isEqualTo("gateway.internal");
	}

}
