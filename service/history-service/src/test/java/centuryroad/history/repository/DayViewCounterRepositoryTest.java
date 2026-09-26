package centuryroad.history.repository;

import centuryroad.history.TestcontainersConfiguration;
import centuryroad.history.model.DayViewCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The atomic upsert against a real Postgres - what an H2 or mocked run would not really
 * exercise: the ON CONFLICT clause, and that two increments of the same day accumulate
 * rather than overwrite.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DayViewCounterRepositoryTest {

    @Autowired
    private DayViewCounterRepository repository;

    @Test
    void incrementingANewDayCreatesItAtOne() {
        repository.increment(10, 16);

        List<DayViewCounter> all = repository.findByOrderByViewCountDesc(PageRequest.of(0, 10));

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getMonth()).isEqualTo((short) 10);
        assertThat(all.get(0).getDay()).isEqualTo((short) 16);
        assertThat(all.get(0).getViewCount()).isEqualTo(1);
    }

    @Test
    void incrementingTheSameDayAgainAccumulatesRatherThanOverwriting() {
        repository.increment(10, 16);
        repository.increment(10, 16);
        repository.increment(10, 16);

        List<DayViewCounter> all = repository.findByOrderByViewCountDesc(PageRequest.of(0, 10));

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getViewCount()).isEqualTo(3);
    }

    @Test
    void topResultsComeMostViewedFirst_respectingTheLimit() {
        repository.increment(1, 1);
        repository.increment(2, 2);
        repository.increment(2, 2);
        repository.increment(3, 3);
        repository.increment(3, 3);
        repository.increment(3, 3);

        List<DayViewCounter> top2 = repository.findByOrderByViewCountDesc(PageRequest.of(0, 2));

        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).getDay()).isEqualTo((short) 3);
        assertThat(top2.get(0).getViewCount()).isEqualTo(3);
        assertThat(top2.get(1).getDay()).isEqualTo((short) 2);
        assertThat(top2.get(1).getViewCount()).isEqualTo(2);
    }
}
