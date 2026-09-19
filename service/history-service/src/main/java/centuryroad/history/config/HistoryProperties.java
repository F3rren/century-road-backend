package centuryroad.history.config;

import centuryroad.history.model.Language;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Every tunable of this service, in one place. Validated, so a missing contact address or a
 * zero size stops the service at startup with the offending property named, instead of
 * surfacing as a puzzling failure on the first request. Values live in application.properties.
 */
@Validated
@ConfigurationProperties(prefix = "history")
public record HistoryProperties(
        @NotNull Language fallbackLanguage,
        @Valid @NotNull Cache cache,
        @Valid @NotNull Wikipedia wikipedia,
        @Valid @NotNull Resilience resilience) {

    /**
     * freshTtl: how long a copy is served without asking Wikipedia again. maxStale: how long
     * a copy stays usable when Wikipedia cannot be reached to refresh it. maxEntries bounds
     * memory: 366 days times two languages is 732 keys, so the default leaves room for a
     * full year in both.
     */
    public record Cache(@NotNull Duration freshTtl, @NotNull Duration maxStale, @Positive int maxEntries) {
    }

    /**
     * hostTemplate carries a {lang} placeholder and is the only place the host is written
     * down (it exists so tests can point it at a local server). contact is required and has
     * no default: Wikimedia's User-Agent policy asks every client to say who to reach when
     * it misbehaves, and a placeholder would defeat the purpose.
     */
    public record Wikipedia(@NotBlank String hostTemplate,
                            @NotBlank String clientName,
                            @NotBlank String contact,
                            @NotNull Duration connectTimeout,
                            @NotNull Duration readTimeout,
                            @Positive int maxBodyBytes,
                            @NotNull Duration defaultCooldown,
                            @NotNull Duration maxCooldown) {

        public String userAgent() {
            return clientName + " (" + contact + ") spring-restclient";
        }
    }

    public record Resilience(@Positive int maxConcurrentCalls,
                             @NotNull Duration bulkheadMaxWait,
                             @Positive int retryAttempts,
                             @NotNull Duration retryWait,
                             @NotNull Duration breakerOpenDuration) {
    }
}
