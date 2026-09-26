package centuryroad.history.repository;

import centuryroad.history.model.DayViewCounter;
import centuryroad.history.model.DayViewCounterId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DayViewCounterRepository extends JpaRepository<DayViewCounter, DayViewCounterId> {

    // An upsert, not read-increment-write: two requests for the same day at the same instant
    // must both land, and a JPA load-then-save round trip would let one overwrite the other.
    // ON CONFLICT makes the increment atomic in Postgres itself.
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO history.day_views (month, day, view_count)
            VALUES (:month, :day, 1)
            ON CONFLICT (month, day) DO UPDATE SET view_count = history.day_views.view_count + 1
            """, nativeQuery = true)
    void increment(@Param("month") int month, @Param("day") int day);

    List<DayViewCounter> findByOrderByViewCountDesc(Pageable pageable);
}
