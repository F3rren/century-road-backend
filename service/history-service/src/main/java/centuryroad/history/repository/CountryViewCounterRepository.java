package centuryroad.history.repository;

import centuryroad.history.model.CountryViewCounter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CountryViewCounterRepository extends JpaRepository<CountryViewCounter, String> {

    // Same atomic upsert as DayViewCounterRepository.increment - see its comment.
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO history.country_views (country_code, view_count)
            VALUES (:code, 1)
            ON CONFLICT (country_code) DO UPDATE SET view_count = history.country_views.view_count + 1
            """, nativeQuery = true)
    void increment(@Param("code") String countryCode);

    List<CountryViewCounter> findByOrderByViewCountDesc(Pageable pageable);
}
