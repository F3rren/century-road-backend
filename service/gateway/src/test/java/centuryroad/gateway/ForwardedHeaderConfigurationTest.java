package centuryroad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

class ForwardedHeaderConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ForwardedHeaderConfiguration.class);

	@Test
	void theFrameworkStrategyGetsTheTransformerThatTrustsOnlyXForwarded() {
		runner.withPropertyValues("server.forward-headers-strategy=framework")
				.run(context -> assertThat(context)
						.getBean(ForwardedHeaderTransformer.class)
						.isInstanceOf(XForwardedOnlyHeaderTransformer.class));
	}

	@Test
	void withoutTheStrategyNoForwardingHeaderIsTrusted() {
		// The dev profile, and any run that does not sit behind the proxy.
		runner.run(context -> assertThat(context).doesNotHaveBean(ForwardedHeaderTransformer.class));
	}

	@Test
	void anotherStrategyGetsNoTransformerEither() {
		runner.withPropertyValues("server.forward-headers-strategy=none")
				.run(context -> assertThat(context).doesNotHaveBean(ForwardedHeaderTransformer.class));
	}

}
