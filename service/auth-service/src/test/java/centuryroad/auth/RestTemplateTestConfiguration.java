package centuryroad.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * The JDK's default HttpURLConnection-backed client cannot replay a streamed
 * POST body
 * to retry after a 401 ("cannot retry due to server authentication, in
 * streaming mode"),
 * so any test that expects a 401 from a POST/PUT with a body needs
 * TestRestTemplate on a
 * client that doesn't have that limitation - Apache HttpClient, not a streaming
 * tweak on
 * SimpleClientHttpRequestFactory (tried first; the JDK still enters the same
 * failing path).
 */
@TestConfiguration(proxyBeanMethods = false)
public class RestTemplateTestConfiguration {

    @Bean
    RestTemplateBuilder restTemplateBuilder() {
        return new RestTemplateBuilder().requestFactory(() -> new HttpComponentsClientHttpRequestFactory());
    }
}
