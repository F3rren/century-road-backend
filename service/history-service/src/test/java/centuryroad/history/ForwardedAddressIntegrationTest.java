package centuryroad.history;

import centuryroad.history.repository.ErrorReportRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim the report limit rests on, tested instead of assumed: with the setting the prod profile
 * uses, the address the limiter counts is the caller's, taken from the Forwarded header the gateway
 * adds - not the gateway's own address, which every visitor would otherwise share.
 *
 * It sets the property by hand because the prod profile cannot be activated in a test (it needs
 * the production port and database), and application-prod.yml is checked below to hold the same
 * value. Its own class, so the limiter is not shared with another test's reports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=framework")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ForwardedAddressIntegrationTest {

    private static final String REPORT = """
            {"target":{"type":"INSIGHT","slug":"test-a"},"category":"OTHER",
             "message":"Una segnalazione di prova abbastanza lunga."}""";

    @LocalServerPort
    private int port;

    @Autowired
    private ErrorReportRepository reports;

    @AfterEach
    void clean() {
        reports.deleteAll();
    }

    // Not TestRestTemplate: its Apache client retries a 429 for as long as Retry-After says.
    private int post(String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/history/reports"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(REPORT));
        if (forwardedFor != null) {
            request.header("Forwarded", "for=" + forwardedFor);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void theProdProfileSetsTheSameStrategyThisTestRunsWith() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));

        assertThat(yaml.getObject()).isNotNull()
                .containsEntry("server.forward-headers-strategy", "framework");
    }

    @Test
    void eachCallerBehindTheProxyHasAQuotaOfTheirOwn() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(post("203.0.113.5")).isEqualTo(201);
        }

        // The sixth from the same caller is refused...
        assertThat(post("203.0.113.5")).isEqualTo(429);
        // ...and somebody else, through the same proxy and the same connection address, is not.
        assertThat(post("203.0.113.6")).isEqualTo(201);
        assertThat(reports.count()).isEqualTo(6);
    }

    @Test
    void anIpv6CallerIsCountedPerSlash64_notPerAddress() throws Exception {
        // Not the caller above: the limiter outlives each test, and that one spent its five.
        assertThat(post("\"[2001:db8:aaaa:1::1]\"")).isEqualTo(201);
        assertThat(post("\"[2001:db8:aaaa:1::2]\"")).isEqualTo(201);
        assertThat(post("\"[2001:db8:aaaa:1::3]\"")).isEqualTo(201);
        assertThat(post("\"[2001:db8:aaaa:1::4]\"")).isEqualTo(201);
        assertThat(post("\"[2001:db8:aaaa:1::5]\"")).isEqualTo(201);

        assertThat(post("\"[2001:db8:aaaa:1:ffff::9]\"")).isEqualTo(429);
        assertThat(post("\"[2001:db8:aaaa:2::1]\"")).isEqualTo(201);
    }
}
