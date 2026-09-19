package centuryroad.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

/**
 * Replaces the transformer Spring Boot registers when server.forward-headers-strategy is
 * "framework" (the prod profile), so that only X-Forwarded-* is trusted. Boot's own bean backs
 * off because this one exists, and under any other strategy nothing is registered at all: a
 * gateway that trusts no forwarding header still trusts none.
 */
@Configuration(proxyBeanMethods = false)
class ForwardedHeaderConfiguration {

    @Bean
    @ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
    ForwardedHeaderTransformer forwardedHeaderTransformer() {
        return new XForwardedOnlyHeaderTransformer();
    }
}
