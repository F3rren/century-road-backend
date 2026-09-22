package centuryroad.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The header of the OpenAPI document, and the one security scheme the endpoints refer to.
 * Only exists when documentation is switched on (springdoc.api-docs.enabled), so a
 * deployment that leaves it off carries nothing of it.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

    /** What @SecurityRequirement on a controller names. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** The revision of this API's contract, raised by hand when it changes. It is not the
     *  artifact's version, which says nothing to somebody reading the documentation. */
    private static final String API_VERSION = "0.1";

    @Bean
    OpenAPI authOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Century Road - auth-service")
                        .version(API_VERSION)
                        .description("Sign-in, token renewal, the caller's own profile and user administration. "
                                + "Log in at `/api/auth/login`, then send the access token as `Authorization: Bearer <token>`. "
                                + "Every answer is wrapped in the same envelope (`success`, `data`, `error`, ...)."))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("The access token from `/api/auth/login` or `/api/auth/refresh`. "
                                + "It is short-lived: renew it with the refresh token.")))
                // Relative on purpose. The document is fetched by a proxy from inside the
                // network, where this service's own host name means nothing to a browser: a
                // relative server makes "Try it out" call whatever origin served the page,
                // which is the gateway.
                .servers(List.of(new Server().url("/").description("The origin this document was loaded from")));
    }

    /**
     * A field validated with "(?i)admin|user" would be published with that as its pattern. JSON
     * Schema patterns are ECMAScript regular expressions, where an inline flag like "(?i)" is a
     * syntax error, so a client generator or a form library reading the document would fail on
     * it. The values the field accepts are listed as an enum next to it, which says the same
     * thing in a way every reader understands.
     *
     * Done on the finished document, not while each property is built: the validation
     * annotations are applied at a point a per-property customizer cannot be relied on to
     * come after.
     */
    @Bean
    OpenApiCustomizer withoutRegexFlagsJavaScriptCannotRead() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
                if (schema.getProperties() == null) {
                    continue;
                }
                for (Schema<?> property : schema.getProperties().values()) {
                    if (property.getPattern() != null && property.getPattern().startsWith("(?")) {
                        property.setPattern(null);
                    }
                }
            }
        };
    }
}
