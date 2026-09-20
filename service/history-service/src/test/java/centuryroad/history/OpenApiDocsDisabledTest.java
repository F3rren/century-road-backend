package centuryroad.history;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a deployment gets unless somebody switches documentation on: nothing. The gateway is
 * public in production, so an API description is something to enable on purpose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiDocsDisabledTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void noDocumentIsServedByDefault() {
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void noInteractivePageIsServedByDefaultEither() {
        assertThat(rest.getForEntity("/swagger-ui.html", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
