package centuryroad.history.query;

import centuryroad.history.model.Language;

/**
 * A request for one country's timeline, validated with the same rules and error codes as the
 * rest of the API: the country code as RandomEventQuery.parseCountry checks it, the language and
 * the year range as OnThisDayQuery does. No years at all means every year.
 */
public record TimelineQuery(String countryCode, Language language, YearRange years) {

    public static TimelineQuery of(String countryCode, String lang, Integer fromYear, Integer toYear) {
        return new TimelineQuery(RandomEventQuery.parseCountry(countryCode), OnThisDayQuery.parseLanguage(lang),
                OnThisDayQuery.parseYears(null, fromYear, toYear));
    }
}
