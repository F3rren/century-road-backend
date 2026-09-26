package centuryroad.history.repository;

import centuryroad.history.TestcontainersConfiguration;
import centuryroad.history.model.CountryViewCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Same atomic-upsert contract as DayViewCounterRepositoryTest, keyed on a country code
 *  instead of a calendar day. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CountryViewCounterRepositoryTest {

    @Autowired
    private CountryViewCounterRepository repository;

    @Test
    void incrementingANewCountryCreatesItAtOne() {
        repository.increment("IT");

        List<CountryViewCounter> all = repository.findByOrderByViewCountDesc(PageRequest.of(0, 10));

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getCountryCode()).isEqualTo("IT");
        assertThat(all.get(0).getViewCount()).isEqualTo(1);
    }

    @Test
    void incrementingTheSameCountryAgainAccumulatesRatherThanOverwriting() {
        repository.increment("IT");
        repository.increment("IT");
        repository.increment("IT");

        List<CountryViewCounter> all = repository.findByOrderByViewCountDesc(PageRequest.of(0, 10));

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getViewCount()).isEqualTo(3);
    }

    @Test
    void topResultsComeMostViewedFirst_respectingTheLimit() {
        repository.increment("FR");
        repository.increment("DE");
        repository.increment("DE");
        repository.increment("IT");
        repository.increment("IT");
        repository.increment("IT");

        List<CountryViewCounter> top2 = repository.findByOrderByViewCountDesc(PageRequest.of(0, 2));

        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).getCountryCode()).isEqualTo("IT");
        assertThat(top2.get(0).getViewCount()).isEqualTo(3);
        assertThat(top2.get(1).getCountryCode()).isEqualTo("DE");
        assertThat(top2.get(1).getViewCount()).isEqualTo(2);
    }
}
