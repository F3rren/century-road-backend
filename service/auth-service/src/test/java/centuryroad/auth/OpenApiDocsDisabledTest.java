package centuryroad.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a deployment gets unless somebody switches documentation on: nothing, and no way to
 * read it without a token either. This service authenticates every route but the ones that
 * hand out tokens, and the documentation route is only opened when the documentation is on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, RestTemplateTestConfiguration.class})
@ActiveProfiles("test")
class OpenApiDocsDisabledTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void noDocumentIsServedByDefault_andAskingWithoutATokenIsRefusedLikeAnyOtherRoute() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).doesNotContain("openapi");
    }
}
