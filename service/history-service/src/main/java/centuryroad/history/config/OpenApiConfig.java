package centuryroad.history.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The header of the OpenAPI document. Only exists when documentation is switched on
 * (springdoc.api-docs.enabled), so a deployment that leaves it off carries nothing of it.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

    /** The revision of this API's contract, raised by hand when it changes. It is not the
     *  artifact's version, which says nothing to somebody reading the documentation. */
    private static final String API_VERSION = "0.1";

    @Bean
    OpenAPI historyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Century Road - history-service")
                        .version(API_VERSION)
                        .description("What happened on a calendar day, from Wikipedia's \"On this day\" feed. "
                                + "Public: no token, nothing per-user. The text is Wikipedia's, under CC BY-SA 4.0: "
                                + "whatever shows it must credit Wikipedia, link the article and name the licence "
                                + "(the answer carries `attribution` for that).")
                        .license(new License().name("CC BY-SA 4.0 (the content)")
                                .url("https://creativecommons.org/licenses/by-sa/4.0/")))
                // Relative on purpose. The document is fetched by a proxy from inside the
                // network, where this service's own host name means nothing to a browser: a
                // relative server makes "Try it out" call whatever origin served the page,
                // which is the gateway.
                .servers(List.of(new Server().url("/").description("The origin this document was loaded from")));
    }
}
