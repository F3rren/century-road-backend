package centuryroad.history.config;

import centuryroad.history.model.Language;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A misconfigured service should refuse to start and say which property is wrong, instead of
 * failing on its first request. The contact address is the one that matters most: it has no
 * default, because Wikimedia's User-Agent policy needs a real one.
 */
class HistoryPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(HistoryProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    private static List<String> valid() {
        return new ArrayList<>(List.of(
                "history.fallback-language=en",
                "history.cache.fresh-ttl=6h", "history.cache.max-stale=7d", "history.cache.max-entries=800",
                "history.wikipedia.host-template=https://{lang}.wikipedia.org",
                "history.wikipedia.client-name=CenturyRoad-History/0.1",
                "history.wikipedia.contact=https://example.invalid/contact",
                "history.wikipedia.connect-timeout=2s", "history.wikipedia.read-timeout=5s",
                "history.wikipedia.max-body-bytes=8388608",
                "history.wikipedia.default-cooldown=5s", "history.wikipedia.max-cooldown=10m",
                "history.resilience.max-concurrent-calls=3", "history.resilience.bulkhead-max-wait=1s",
                "history.resilience.retry-attempts=2", "history.resilience.retry-wait=300ms",
                "history.resilience.breaker-open-duration=30s"));
    }

    private static String[] without(String key) {
        List<String> values = valid();
        values.removeIf(v -> v.startsWith(key + "="));
        return values.toArray(String[]::new);
    }

    private static String[] with(String override) {
        List<String> values = valid();
        values.removeIf(v -> v.startsWith(override.substring(0, override.indexOf('=') + 1)));
        values.add(override);
        return values.toArray(String[]::new);
    }

    @Test
    void aCompleteConfigurationBindsWithDurationsAndTheLanguageParsed() {
        runner.withPropertyValues(valid().toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            HistoryProperties properties = context.getBean(HistoryProperties.class);
            assertThat(properties.fallbackLanguage()).isEqualTo(Language.EN);
            assertThat(properties.cache().freshTtl()).isEqualTo(Duration.ofHours(6));
            assertThat(properties.cache().maxStale()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.wikipedia().maxCooldown()).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    void theUserAgentNamesTheClientAndSaysWhoToContact() {
        runner.withPropertyValues(valid().toArray(String[]::new)).run(context ->
                assertThat(context.getBean(HistoryProperties.class).wikipedia().userAgent())
                        .isEqualTo("CenturyRoad-History/0.1 (https://example.invalid/contact) spring-restclient"));
    }

    @Test
    void withoutAContactTheServiceDoesNotStart() {
        runner.withPropertyValues(without("history.wikipedia.contact")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("contact");
        });
    }

    @Test
    void aBlankContactDoesNotStartEither() {
        runner.withPropertyValues(with("history.wikipedia.contact=   ")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("contact");
        });
    }

    @Test
    void aMissingFallbackLanguageDoesNotStart() {
        runner.withPropertyValues(without("history.fallback-language")).run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void aLanguageThatIsNotSupportedDoesNotStart() {
        runner.withPropertyValues(with("history.fallback-language=fr")).run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void aCacheThatCouldHoldNothingDoesNotStart() {
        runner.withPropertyValues(with("history.cache.max-entries=0")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("maxEntries");
        });
    }

    @Test
    void aMissingTimeoutDoesNotStart() {
        runner.withPropertyValues(without("history.wikipedia.read-timeout")).run(context ->
                assertThat(context).hasFailed());
    }
}
