package centuryroad.history.query;

import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.model.Language;

import java.util.regex.Pattern;

/**
 * A request for one country's timeline, validated with the same rules and error codes as the
 * rest of the API: the country code as TrackingController checks it, the language and the
 * year range as OnThisDayQuery does. No years at all means every year.
 */
public record TimelineQuery(String countryCode, Language language, YearRange years) {

    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    public static TimelineQuery of(String countryCode, String lang, Integer fromYear, Integer toYear) {
        if (countryCode == null || !COUNTRY_CODE.matcher(countryCode).matches()) {
            throw new InvalidRequestException("INVALID_COUNTRY_CODE",
                    "code must be two uppercase ISO 3166-1 alpha-2 letters, got: " + countryCode,
                    "Codice paese non valido.");
        }
        return new TimelineQuery(countryCode, OnThisDayQuery.parseLanguage(lang),
                OnThisDayQuery.parseYears(null, fromYear, toYear));
    }
}
