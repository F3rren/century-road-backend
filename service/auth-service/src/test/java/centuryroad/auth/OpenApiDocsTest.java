package centuryroad.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI document this service publishes when documentation is switched on. Two tests
 * matter for the future: one walks the endpoints the application really has and fails for
 * any the document lacks, and one fails for any field of any answer with no description, so a
 * new endpoint cannot be added undocumented.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=true")
@Import({TestcontainersConfiguration.class, RestTemplateTestConfiguration.class})
@ActiveProfiles("test")
class OpenApiDocsTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode spec() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private JsonNode operation(String method, String path) throws Exception {
        JsonNode operation = spec().path("paths").path(path).path(method);
        assertThat(operation.isMissingNode()).as("%s %s is in the document", method.toUpperCase(), path).isFalse();
        return operation;
    }

    private static List<String> responseCodes(JsonNode operation) {
        List<String> codes = new ArrayList<>();
        operation.path("responses").fieldNames().forEachRemaining(codes::add);
        return codes;
    }

    private static boolean needsBearerToken(JsonNode operation) {
        for (JsonNode requirement : operation.path("security")) {
            if (requirement.has("bearerAuth")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theDocumentIsServedWithoutAToken_becauseTheGatewayReadsItWithoutOne() throws Exception {
        JsonNode spec = spec();

        assertThat(spec.path("openapi").asText()).startsWith("3.");
        assertThat(spec.path("info").path("title").asText()).contains("auth");
    }

    @Test
    void callsAreMadeAgainstTheOriginTheDocumentWasLoadedFrom() throws Exception {
        JsonNode servers = spec().path("servers");

        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).path("url").asText()).isEqualTo("/");
    }

    @Test
    void theBearerSchemeIsDeclaredSoTheInteractivePageCanSendAToken() throws Exception {
        JsonNode scheme = spec().path("components").path("securitySchemes").path("bearerAuth");

        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    void onlyTheEndpointsThatNeedATokenSayTheyDo() throws Exception {
        assertThat(needsBearerToken(operation("get", "/api/me"))).isTrue();
        assertThat(needsBearerToken(operation("get", "/api/admin/users"))).isTrue();
        assertThat(needsBearerToken(operation("post", "/api/admin/users"))).isTrue();
        assertThat(needsBearerToken(operation("put", "/api/admin/users/{id}"))).isTrue();
        assertThat(needsBearerToken(operation("delete", "/api/admin/users/{id}"))).isTrue();

        assertThat(needsBearerToken(operation("post", "/api/auth/login"))).isFalse();
        assertThat(needsBearerToken(operation("post", "/api/auth/refresh"))).isFalse();
        assertThat(needsBearerToken(operation("post", "/api/auth/logout"))).isFalse();
    }

    @Test
    void loginAndRefreshDeclareTheirRefusals() throws Exception {
        assertThat(responseCodes(operation("post", "/api/auth/login"))).contains("200", "400", "401", "429");
        assertThat(responseCodes(operation("post", "/api/auth/refresh"))).contains("200", "400", "401");
        assertThat(responseCodes(operation("post", "/api/auth/logout"))).contains("200", "400");
    }

    @Test
    void everyAdminOperationDeclaresThatItNeedsALoginAndTheAdminRole() throws Exception {
        for (String[] endpoint : new String[][]{{"get", "/api/admin/users"}, {"post", "/api/admin/users"},
                {"put", "/api/admin/users/{id}"}, {"delete", "/api/admin/users/{id}"}}) {
            assertThat(responseCodes(operation(endpoint[0], endpoint[1])))
                    .as("%s %s", endpoint[0], endpoint[1]).contains("401", "403");
        }
    }

    @Test
    void theAdminOperationsThatCanRunIntoAConflictOrAMissingUserSayWhich() throws Exception {
        assertThat(responseCodes(operation("post", "/api/admin/users"))).contains("201", "400", "409");
        assertThat(responseCodes(operation("put", "/api/admin/users/{id}"))).contains("200", "400", "404", "409");
        assertThat(responseCodes(operation("delete", "/api/admin/users/{id}"))).contains("200", "404");
        assertThat(responseCodes(operation("get", "/api/me"))).contains("200", "401", "404");
    }

    @Test
    void everyAnswerIsDeclaredAsJson_notAsAnything() throws Exception {
        List<String> notJson = new ArrayList<>();

        spec().path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(method ->
                method.getValue().path("responses").fields().forEachRemaining(response ->
                        response.getValue().path("content").fieldNames().forEachRemaining(mediaType -> {
                            if (!"application/json".equals(mediaType)) {
                                notJson.add(method.getKey() + " " + path.getKey() + " " + response.getKey() + " " + mediaType);
                            }
                        }))));

        assertThat(notJson).isEmpty();
    }

    @Test
    void passwordsAreMarkedAsPasswordsAndAsWriteOnly() throws Exception {
        JsonNode schemas = spec().path("components").path("schemas");

        for (String schema : new String[]{"LoginRequest", "CreateUserRequest", "UpdateUserRequest"}) {
            JsonNode password = schemas.path(schema).path("properties").path("password");
            assertThat(password.path("format").asText()).as(schema + ".password format").isEqualTo("password");
            assertThat(password.path("writeOnly").asBoolean()).as(schema + ".password writeOnly").isTrue();
        }
    }

    @Test
    void noPatternUsesJavaRegexFlagsThatJavaScriptCannotRead_becauseAClientGeneratorWouldChokeOnThem() throws Exception {
        // The role is validated with "(?i)admin|user". JSON Schema patterns are ECMAScript, where
        // "(?i)" is a syntax error, and the allowed values are listed as an enum anyway.
        List<String> unreadable = new ArrayList<>();

        spec().path("components").path("schemas").fields().forEachRemaining(schema ->
                schema.getValue().path("properties").fields().forEachRemaining(property -> {
                    String pattern = property.getValue().path("pattern").asText();
                    if (pattern.startsWith("(?")) {
                        unreadable.add(schema.getKey() + "." + property.getKey() + " = " + pattern);
                    }
                }));

        assertThat(unreadable).isEmpty();
    }

    @Test
    void theRoleStillListsWhatItAccepts() throws Exception {
        JsonNode role = spec().path("components").path("schemas").path("CreateUserRequest")
                .path("properties").path("role");

        List<String> values = new ArrayList<>();
        role.path("enum").forEach(node -> values.add(node.asText()));
        assertThat(values).containsExactly("user", "admin");
    }

    @Test
    void everyFieldOfEveryRequestAndAnswerIsDescribed_becauseTheNamesAloneDoNotSayWhatTheyMean() throws Exception {
        List<String> undescribed = new ArrayList<>();

        spec().path("components").path("schemas").fields().forEachRemaining(schema ->
                schema.getValue().path("properties").fields().forEachRemaining(property -> {
                    boolean isReference = property.getValue().has("$ref");
                    if (!isReference && property.getValue().path("description").asText().isBlank()) {
                        undescribed.add(schema.getKey() + "." + property.getKey());
                    }
                }));

        assertThat(undescribed).as("fields with no @Schema description").isEmpty();
    }

    @Test
    void everyEndpointTheApplicationHasIsInTheDocumentWithASummary() throws Exception {
        JsonNode paths = spec().path("paths");
        List<String> undocumented = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping : handlerMapping.getHandlerMethods().entrySet()) {
            // Only this application's own controllers: Boot's error page and springdoc's own
            // endpoints are not part of the API.
            if (!mapping.getValue().getBeanType().getName().startsWith("centuryroad.")) {
                continue;
            }
            PathPatternsRequestCondition patterns = Objects.requireNonNull(mapping.getKey().getPathPatternsCondition(),
                    "path patterns are always parsed in Spring Boot 3");
            for (String path : patterns.getPatternValues()) {
                mapping.getKey().getMethodsCondition().getMethods().forEach(method -> {
                    String summary = paths.path(path).path(method.name().toLowerCase()).path("summary").asText();
                    if (summary.isBlank()) {
                        undocumented.add(method + " " + path);
                    }
                });
            }
        }

        assertThat(undocumented).as("endpoints with no documentation or no summary").isEmpty();
    }
}
