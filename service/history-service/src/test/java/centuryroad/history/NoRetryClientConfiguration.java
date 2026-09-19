package centuryroad.history;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Gives TestRestTemplate a client that never retries on its own. The default is Apache
 * HttpClient, which answers a 503 by waiting out its Retry-After and asking again - so a test
 * that checks how this service reports a rate limit would find the client quietly making
 * a second request half a minute later, and count Wikipedia hits it did not cause.
 */
@TestConfiguration
public class NoRetryClientConfiguration {

    @Bean
    public RestTemplateBuilder restTemplateBuilder() {
        return new RestTemplateBuilder().requestFactory(SimpleClientHttpRequestFactory::new);
    }
}
