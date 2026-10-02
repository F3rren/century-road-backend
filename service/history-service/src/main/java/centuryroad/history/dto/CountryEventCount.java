package centuryroad.history.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many events the country index holds for a country, in one language.")
public record CountryEventCount(
        @Schema(description = "ISO 3166-1 alpha-2 country code.", example = "IT") String countryCode,
        @Schema(description = "How many events with a year are placed in this country.", example = "512") long eventCount) {
}
