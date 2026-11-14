package centuryroad.auth;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published API, written down and held still: the OpenAPI document this service serves is
 * compared with the one committed in docs/openapi, and a change to either is a failing test until
 * the two agree again.
 *
 * Why a committed copy, and not only the checks in OpenApiDocsTest: those say the document is
 * complete, this says it is the same as it was. That is what a refactor must not change, and what
 * a framework upgrade (Spring Boot 4 changes how Jackson writes dates) can change without anyone
 * touching the code.
 *
 * After an intended change, regenerate it and review the diff like any other:
 *
 *     ./mvnw test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true
 *
 * The file is canonical on purpose - object keys sorted, lists of plain strings sorted, the
 * server address (a random port in a test) left out - so it only changes when the contract does.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=true")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RestTemplateTestConfiguration.class})
class OpenApiSnapshotTest {

    /** Relative to the service's own directory, where Maven runs the tests. */
    private static final Path SNAPSHOT = Path.of("../../docs/openapi/auth-service.json");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void thePublishedContractIsTheCommittedOne() throws Exception {
        String actual = canonicalSpec();

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual);
            return;
        }

        assertThat(SNAPSHOT).as("the committed contract; create it with -Dopenapi.update=true").exists();
        String committed = Files.readString(SNAPSHOT);
        // A boolean, not isEqualTo(committed): a failed string comparison would print both whole
        // documents, a hundred kilobytes, and bury the one line that says what changed.
        assertThat(actual.equals(committed))
                .as("The API this service publishes is not the one in %s.%n%s%nIf the change is intended, "
                                + "regenerate the file (./mvnw test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true) "
                                + "and commit it with the change.",
                        SNAPSHOT, firstDifference(committed, actual))
                .isTrue();
    }

    private String canonicalSpec() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = mapper.readValue(response.getBody(), Map.class);
        spec.remove("servers");
        return mapper.writeValueAsString(canonical(spec)) + "\n";
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> items = list.stream().map(OpenApiSnapshotTest::canonical).collect(Collectors.toList());
            if (items.stream().allMatch(String.class::isInstance)) {
                items.sort(Comparator.comparing(Object::toString));
            }
            return items;
        }
        return value;
    }

    /** The first line where the two documents part, with its number: enough to find the change. */
    private static String firstDifference(String committed, String actual) {
        String[] before = committed.split("\n", -1);
        String[] after = actual.split("\n", -1);
        for (int i = 0; i < Math.max(before.length, after.length); i++) {
            String left = i < before.length ? before[i] : "(end of file)";
            String right = i < after.length ? after[i] : "(end of file)";
            if (!left.equals(right)) {
                return "First difference at line " + (i + 1) + ":\n  committed: " + left.strip() + "\n  published: " + right.strip();
            }
        }
        return "";
    }
}
