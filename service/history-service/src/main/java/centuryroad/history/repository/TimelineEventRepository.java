package centuryroad.history.repository;

import centuryroad.history.dto.CountryEventCount;
import centuryroad.history.dto.IndexCoverage;
import centuryroad.history.model.TimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.MonthDay;
import java.util.List;
import java.util.Optional;

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

    // "Sorprendimi". Postgres' random() over a few thousand rows is a millisecond; a count and an
    // offset would be two round trips to avoid it. Two queries, not one with an optional
    // country: a null bound to "(:code is null or ...)" has no type for Postgres to infer.
    @Query(value = "SELECT * FROM history.timeline_events WHERE language = :language"
            + " AND year BETWEEN :from AND :to ORDER BY random() LIMIT 1", nativeQuery = true)
    Optional<TimelineEvent> randomEvent(@Param("language") String language, @Param("from") int fromYear,
            @Param("to") int toYear);

    @Query(value = "SELECT * FROM history.timeline_events WHERE language = :language AND country_code = :code"
            + " AND year BETWEEN :from AND :to ORDER BY random() LIMIT 1", nativeQuery = true)
    Optional<TimelineEvent> randomEventIn(@Param("language") String language, @Param("code") String countryCode,
            @Param("from") int fromYear, @Param("to") int toYear);

    // "Nello stesso periodo": every country's events in a window of years. The window is capped
    // by the caller's query, so this is at most a few thousand rows, trimmed per country in Java.
    @Query("select e from TimelineEvent e where e.language = :language and e.year between :from and :to"
            + " order by e.countryCode, e.year, e.month, e.day, e.id")
    List<TimelineEvent> inYears(@Param("language") String language, @Param("from") int fromYear,
            @Param("to") int toYear);

    // What the index covers, per language, for the sources page. Nothing when it is still empty.
    @Query("select new centuryroad.history.dto.IndexCoverage(e.language, count(e), count(distinct e.countryCode),"
            + " min(e.year), max(e.year), max(e.indexedAt)) from TimelineEvent e group by e.language"
            + " order by e.language")
    List<IndexCoverage> coverage();
}
