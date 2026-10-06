package centuryroad.history.query;

import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.model.Language;

import java.util.regex.Pattern;

/**
 * "Sorprendimi": a random event, from the filters the visitor has active. The filters are
 * the ones the rest of the API already has - a country, a range of years - with the same
 * rules and error codes. No filter at all means any event in the index.
 */
public record RandomEventQuery(Language language, String countryCode, YearRange years) {

    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    public static RandomEventQuery of(String lang, String countryCode, Integer fromYear, Integer toYear) {
        return new RandomEventQuery(OnThisDayQuery.parseLanguage(lang), parseCountry(countryCode),
                OnThisDayQuery.parseYears(null, fromYear, toYear));
    }

    /** Null when none was given; the same rule and code as everywhere a country code is taken. */
    static String parseCountry(String countryCode) {
        if (countryCode == null) {
            return null;
        }
        if (!COUNTRY_CODE.matcher(countryCode).matches()) {
            throw new InvalidRequestException("INVALID_COUNTRY_CODE",
                    "country must be two uppercase ISO 3166-1 alpha-2 letters, got: " + countryCode,
                    "Codice paese non valido.");
        }
        return countryCode;
    }
}
