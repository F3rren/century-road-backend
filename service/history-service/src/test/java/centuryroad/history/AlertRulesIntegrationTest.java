package centuryroad.history;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Grafana alert rules in {@code infra/grafana} are only YAML: nothing starts a Grafana in
 * CI, so a rule can quietly watch a series that does not exist - two of them once did, both
 * reading the bulkhead whatever their title said, and could never fire for what they claimed.
 * This test closes the gap from the other side: every {@code resilience4j_*} / {@code history_*}
 * series a rule's query names, label matchers included, must be in what this service really
 * serves on {@code /actuator/prometheus}.
 *
 * Each series is made to exist the way it does in production: the breaker and the bulkhead are
 * registered with the client, the timer by one Wikipedia call, and the stale counter by a
 * refresh that fails while an old copy is still held.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "history.cache.fresh-ttl=1ms")
@ActiveProfiles("test")
@Import({NoRetryClientConfiguration.class, TestcontainersConfiguration.class})
class AlertRulesIntegrationTest {

    private static final Path RULES = Path.of("../../infra/grafana/provisioning/alerting/rules.yaml");
    private static final String IT_10_16 = "/it/api/rest_v1/feed/onthisday/all/10/16";
    private static final Pattern SERIES = Pattern.compile("\\b((?:resilience4j|history)_[a-z0-9_]+)(?:\\{([^}]*)})?");
    private static final Pattern MATCHER = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    // Same reason as in HistoryServiceIntegrationTest: listening before Spring builds its context.
    private static final FakeWikipedia wikipedia = FakeWikipedia.start();

    @Autowired
    private TestRestTemplate rest;

    @AfterAll
    static void stopFakeWikipedia() {
        wikipedia.close();
    }

    @DynamicPropertySource
    static void pointAtTheFake(DynamicPropertyRegistry registry) {
        registry.add("history.wikipedia.host-template", () -> wikipedia.baseUrl() + "/{lang}");
    }

    @Test
    void everySeriesAnAlertWatchesIsOneThisServiceActuallyExposes() throws Exception {
        wikipedia.reply(IT_10_16, FakeWikipedia.Reply.json(Fixtures.text("it-10-16.json")));
        assertThat(rest.getForEntity("/api/history/on-this-day/10/16?lang=it", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // The cached copy is now older than the (1 ms) fresh window, so this refresh is attempted -
        // and fails - and the old copy is served as stale.
        wikipedia.reply(IT_10_16, FakeWikipedia.Reply.status(500));
        Thread.sleep(20);
        assertThat(rest.getForEntity("/api/history/on-this-day/10/16?lang=it", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String scrape = rest.getForObject("/actuator/prometheus", String.class);
        Map<String, String> queries = queriesByTitle();

        // The titles this guards, so that a renamed rule cannot slip out of the check unnoticed.
        assertThat(queries).containsKeys("CircuitBreakerOpen", "UpstreamLatencyDegraded", "BulkheadSaturated",
                "StaleServeRateSpike");
        // And what each title promises, which the series check alone would not catch: it is the
        // breaker, not the bulkhead, that CircuitBreakerOpen has to read.
        assertThat(queries.get("CircuitBreakerOpen")).contains("resilience4j_circuitbreaker_state");
        assertThat(queries.get("UpstreamLatencyDegraded")).contains("history_upstream_requests_seconds_sum")
                .contains("history_upstream_requests_seconds_count");
        assertThat(queries.get("BulkheadSaturated")).contains("resilience4j_bulkhead_available_concurrent_calls");
        assertThat(queries.get("StaleServeRateSpike")).contains("history_stale_served_total");

        List<String> missing = new ArrayList<>();
        queries.forEach((title, query) -> {
            Matcher series = SERIES.matcher(query);
            while (series.find()) {
                if (!exposes(scrape, series.group(1), series.group(2))) {
                    missing.add(title + " -> " + series.group());
                }
            }
        });
        assertThat(missing).as("alert series missing from /actuator/prometheus").isEmpty();
    }

    /** True when some sample of {@code name} carries every {@code key="value"} the selector asks for. */
    private static boolean exposes(String scrape, String name, String selector) {
        List<String[]> wanted = new ArrayList<>();
        if (selector != null) {
            Matcher m = MATCHER.matcher(selector);
            while (m.find()) {
                wanted.add(new String[] {m.group(1), m.group(2)});
            }
        }
        return scrape.lines()
                .filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
                .anyMatch(line -> wanted.stream().allMatch(w -> line.contains(w[0] + "=\"" + w[1] + "\"")));
    }

    /** Rule title -> the PromQL of its first query, for every rule that watches one of this service's series. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> queriesByTitle() throws IOException {
        Map<String, String> byTitle = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(RULES)) {
            Map<String, Object> root = new Yaml().load(reader);
            for (Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
                for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) rule.get("data");
                    Map<String, Object> model = (Map<String, Object>) data.get(0).get("model");
                    String query = String.valueOf(model.get("expr"));
                    if (SERIES.matcher(query).find()) {
                        byTitle.put((String) rule.get("title"), query);
                    }
                }
            }
        }
        return byTitle;
    }
}
