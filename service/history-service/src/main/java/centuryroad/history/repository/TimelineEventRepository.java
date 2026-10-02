package centuryroad.history.repository;

import centuryroad.history.dto.CountryEventCount;
import centuryroad.history.model.TimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.MonthDay;
import java.util.List;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, Long> {

    @Modifying
    @Query(value = "DELETE FROM history.timeline_events WHERE language = :language AND month = :month AND day = :day",
            nativeQuery = true)
    void deleteDay(@Param("language") String language, @Param("month") int month, @Param("day") int day);

    // One swap per day: a reader never sees the day half-written, and a failed insert leaves
    // the previous rows in place.
    @Transactional
    default void replaceDay(String language, MonthDay day, List<TimelineEvent> rows) {
        deleteDay(language, day.getMonthValue(), day.getDayOfMonth());
        saveAll(rows);
    }

    @Query("select new centuryroad.history.dto.CountryEventCount(e.countryCode, count(e))"
            + " from TimelineEvent e where e.language = :language"
            + " group by e.countryCode order by e.countryCode")
    List<CountryEventCount> countByCountry(@Param("language") String language);

    @Query("select e from TimelineEvent e where e.language = :language and e.countryCode = :code"
            + " and e.year between :from and :to order by e.year, e.month, e.day, e.id")
    List<TimelineEvent> timeline(@Param("language") String language, @Param("code") String countryCode,
            @Param("from") int fromYear, @Param("to") int toYear);
}
