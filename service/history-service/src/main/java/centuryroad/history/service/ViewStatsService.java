package centuryroad.history.service;

import centuryroad.history.model.CountryViewCounter;
import centuryroad.history.model.DayViewCounter;
import centuryroad.history.repository.CountryViewCounterRepository;
import centuryroad.history.repository.DayViewCounterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.MonthDay;
import java.util.List;

/**
 * Anonymous, aggregate view counting: how many times a calendar day or a country has been
 * viewed, in total. No visitor identifier is ever recorded - see the migration.
 *
 * The two record* methods run off the caller's thread (@Async) and never let a database
 * problem surface to it: on-this-day's own answer must never depend on this service's
 * counters being reachable, so a failure here is logged and swallowed, not thrown.
 */
@Service
public class ViewStatsService {

    private static final Logger log = LoggerFactory.getLogger(ViewStatsService.class);

    private final DayViewCounterRepository dayViews;
    private final CountryViewCounterRepository countryViews;

    public ViewStatsService(DayViewCounterRepository dayViews, CountryViewCounterRepository countryViews) {
        this.dayViews = dayViews;
        this.countryViews = countryViews;
    }

    @Async
    public void recordDayView(MonthDay day) {
        try {
            dayViews.increment(day.getMonthValue(), day.getDayOfMonth());
        } catch (DataAccessException e) {
            log.warn("Failed to record a day view for {}", day, e);
        }
    }

    @Async
    public void recordCountryView(String countryCode) {
        try {
            countryViews.increment(countryCode);
        } catch (DataAccessException e) {
            log.warn("Failed to record a country view for {}", sanitizeForLog(countryCode), e);
        }
    }

    private String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', '_').replace('\r', '_');
    }

    public List<DayViewCounter> topDays(int limit) {
        return dayViews.findByOrderByViewCountDesc(PageRequest.of(0, limit));
    }

    public List<CountryViewCounter> topCountries(int limit) {
        return countryViews.findByOrderByViewCountDesc(PageRequest.of(0, limit));
    }
}
