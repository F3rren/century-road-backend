package centuryroad.history.query;

import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.model.Language;

/**
 * "Nello stesso periodo": what else was in the index around a year, in the countries the
 * visitor is not looking at. span is how many years either side, perCountry how many events
 * of each country to show. Both are capped, so one request is a bounded read.
 */
public record SamePeriodQuery(int year, int span, Language language, String excludeCountry, int perCountry) {

    public static final int DEFAULT_SPAN = 5;
    public static final int MAX_SPAN = 25;
    public static final int DEFAULT_PER_COUNTRY = 3;
    public static final int MAX_PER_COUNTRY = 10;

    public static SamePeriodQuery of(int year, Integer span, String lang, String excludeCountry, Integer perCountry) {
        if (year < OnThisDayQuery.MIN_YEAR || year > OnThisDayQuery.MAX_YEAR) {
            throw new InvalidRequestException("INVALID_YEAR", "year out of range",
                    "L'anno deve essere compreso tra " + OnThisDayQuery.MIN_YEAR + " e " + OnThisDayQuery.MAX_YEAR + ".");
        }
        int s = span == null ? DEFAULT_SPAN : span;
        if (s < 0 || s > MAX_SPAN) {
            throw new InvalidRequestException("INVALID_SPAN", "span must be between 0 and " + MAX_SPAN + ", got " + s,
                    "L'intervallo deve essere compreso tra 0 e " + MAX_SPAN + " anni.");
        }
        int n = perCountry == null ? DEFAULT_PER_COUNTRY : perCountry;
        if (n < 1 || n > MAX_PER_COUNTRY) {
            throw new InvalidRequestException("INVALID_LIMIT",
                    "perCountry must be between 1 and " + MAX_PER_COUNTRY + ", got " + n,
                    "Il numero di eventi per paese deve essere compreso tra 1 e " + MAX_PER_COUNTRY + ".");
        }
        return new SamePeriodQuery(year, s, OnThisDayQuery.parseLanguage(lang),
                RandomEventQuery.parseCountry(excludeCountry), n);
    }

    public int fromYear() {
        return Math.max(OnThisDayQuery.MIN_YEAR, year - span);
    }

    public int toYear() {
        return Math.min(OnThisDayQuery.MAX_YEAR, year + span);
    }
}
