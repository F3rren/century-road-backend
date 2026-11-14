package centuryroad.history.service;

import centuryroad.history.dto.CountryEventCount;
import centuryroad.history.dto.IndexCoverage;
import centuryroad.history.model.Language;
import centuryroad.history.model.TimelineEvent;
import centuryroad.history.query.RandomEventQuery;
import centuryroad.history.query.SamePeriodQuery;
import centuryroad.history.query.TimelineQuery;
import centuryroad.history.repository.TimelineEventRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The country index, as it is read: every question the pages that show it can ask - which countries have
 * events, one country's timeline, a random event, the same years elsewhere, how much the index covers.
 * (TimelineIndexer is the one that writes it.)
 *
 * A controller asks this, not the repository: what a question means in terms of the table - which of two
 * queries answers "a random event" with or without a country, that the repository takes a language as its
 * code - is decided here once, and an architecture rule (HistoryArchitectureTest) keeps the controllers
 * from reaching past it.
 */
@Service
public class TimelineService {

    private final TimelineEventRepository repository;

    public TimelineService(TimelineEventRepository repository) {
        this.repository = repository;
    }

    /** Every country with indexed events in that edition, with how many, ordered by country code. */
    public List<CountryEventCount> countriesWithEvents(Language language) {
        return repository.countByCountry(language.code());
    }

    /** One country's events in one edition within the years asked, oldest first. */
    public List<TimelineEvent> timeline(TimelineQuery query) {
        return repository.timeline(query.language().code(), query.countryCode(), query.years().from(),
                query.years().to());
    }

    /** One event picked at random among those that match: in a country if the query names one, anywhere if not. */
    public Optional<TimelineEvent> randomEvent(RandomEventQuery query) {
        String language = query.language().code();
        return query.countryCode() == null
                ? repository.randomEvent(language, query.years().from(), query.years().to())
                : repository.randomEventIn(language, query.countryCode(), query.years().from(), query.years().to());
    }

    /** Every country's events in the window of years around the one asked about. */
    public List<TimelineEvent> inYears(SamePeriodQuery query) {
        return repository.inYears(query.language().code(), query.fromYear(), query.toYear());
    }

    /** What the index holds, per edition: how many events, countries, which years, how recent. Empty while it is. */
    public List<IndexCoverage> coverage() {
        return repository.coverage();
    }
}
