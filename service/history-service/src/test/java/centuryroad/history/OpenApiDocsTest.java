package centuryroad.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * The OpenAPI document this service publishes when documentation is switched on. The last
 * test is the one that matters for the future: it walks the endpoints the application really
 * has and fails for any that the document does not describe, so a new endpoint cannot be
 * added without its documentation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=true")
@ActiveProfiles("test")
class OpenApiDocsTest {

    private static final String DAY_PATH = "/api/history/on-this-day/{month}/{day}";

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

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        throw new AssertionError("No parameter " + name + " in " + operation.path("operationId").asText());
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    void theDocumentIsServedWithoutAnyToken_becauseTheEndpointItDescribesIsPublicToo() throws Exception {
        JsonNode spec = spec();

        assertThat(spec.path("openapi").asText()).startsWith("3.");
        assertThat(spec.path("info").path("title").asText()).contains("history");
    }

    @Test
    void theOneEndpointIsDescribedWithItsPathParametersAndItsAnswers() throws Exception {
        JsonNode operation = spec().path("paths").path(DAY_PATH).path("get");

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("summary").asText()).isNotBlank();
        assertThat(parameter(operation, "month").path("in").asText()).isEqualTo("path");
        assertThat(parameter(operation, "day").path("in").asText()).isEqualTo("path");
        assertThat(operation.path("responses").fieldNames()).toIterable()
                .contains("200", "400", "502", "503");
    }

    @Test
    void theDateSegmentsAreIntegersWithTheirRange() throws Exception {
        JsonNode operation = spec().path("paths").path(DAY_PATH).path("get");

        assertThat(parameter(operation, "month").path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(parameter(operation, "month").path("schema").path("maximum").asInt()).isEqualTo(12);
        assertThat(parameter(operation, "day").path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(parameter(operation, "day").path("schema").path("maximum").asInt()).isEqualTo(31);
    }

    @Test
    void everyAnswerIsDeclaredAsJson_notAsAnything() throws Exception {
        JsonNode responses = spec().path("paths").path(DAY_PATH).path("get").path("responses");

        for (String code : new String[]{"200", "400", "502", "503"}) {
            assertThat(responses.path(code).path("content").fieldNames()).toIterable()
                    .as("media types of the %s answer", code).containsExactly("application/json");
        }
    }

    @Test
    void theValuesAParameterAcceptsAreListed_notLeftForTheReaderToGuess() throws Exception {
        JsonNode operation = spec().path("paths").path(DAY_PATH).path("get");

        assertThat(texts(parameter(operation, "lang").path("schema").path("enum"))).containsExactly("it", "en");
        assertThat(parameter(operation, "lang").path("schema").path("default").asText()).isEqualTo("it");
        assertThat(texts(parameter(operation, "types").path("schema").path("items").path("enum")))
                .containsExactly("selected", "events", "births", "deaths", "holidays");
    }

    @Test
    void theSuccessBodyIsTheEnvelopeAroundTheDayNotAnUnnamedObject() throws Exception {
        JsonNode spec = spec();
        String ref = spec.path("paths").path(DAY_PATH).path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema").path("$ref").asText();

        assertThat(ref).startsWith("#/components/schemas/");
        JsonNode envelope = spec.path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
        assertThat(envelope.path("properties").fieldNames()).toIterable().contains("success", "data", "sessionId");
    }

    @Test
    void callsAreMadeAgainstTheOriginTheDocumentWasLoadedFrom_soTheyGoThroughTheGatewayThatServedIt() throws Exception {
        // A proxy fetches this document from inside the network, where the service's own host
        // name means nothing to a browser: a relative server is the only one that is always right.
        JsonNode servers = spec().path("servers");

        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).path("url").asText()).isEqualTo("/");
    }

    @Test
    void everyFieldOfEveryAnswerIsDescribed_becauseTheNamesAloneDoNotSayWhatTheyMean() throws Exception {
        List<String> undescribed = new ArrayList<>();

        spec().path("components").path("schemas").fields().forEachRemaining(schema ->
                schema.getValue().path("properties").fields().forEachRemaining(property -> {
                    // A property that is only a reference to another schema is described there.
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
