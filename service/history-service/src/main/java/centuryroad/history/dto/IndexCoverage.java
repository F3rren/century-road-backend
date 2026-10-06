package centuryroad.history.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "What the country index holds for one Wikipedia edition.")
public record IndexCoverage(
        @Schema(description = "The edition: it or en.", example = "it") String language,
        @Schema(description = "How many events with a year are placed in a country.", example = "6800") long eventCount,
        @Schema(description = "In how many countries those events are.", example = "140") long countryCount,
        @Schema(description = "The oldest year of any indexed event. Negative before the common era.", example = "-3000") int oldestYear,
        @Schema(description = "The newest year of any indexed event.", example = "2025") int newestYear,
        @Schema(description = "When the newest of these events was written to the index: how far behind Wikipedia it can be.") OffsetDateTime indexedAt) {
}
