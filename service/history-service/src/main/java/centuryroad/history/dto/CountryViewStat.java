package centuryroad.history.dto;

import centuryroad.history.model.CountryViewCounter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many times a country has been viewed, in total.")
public record CountryViewStat(
        @Schema(description = "ISO 3166-1 alpha-2 country code.", example = "IT") String countryCode,
        @Schema(description = "How many times this country has been viewed, in total.", example = "128") long viewCount) {

    public static CountryViewStat from(CountryViewCounter counter) {
        return new CountryViewStat(counter.getCountryCode(), counter.getViewCount());
    }
}
