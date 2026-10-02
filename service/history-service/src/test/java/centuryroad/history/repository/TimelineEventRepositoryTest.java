package centuryroad.history.repository;

import centuryroad.history.TestcontainersConfiguration;
import centuryroad.history.dto.CountryEventCount;
import centuryroad.history.model.TimelineEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The country index against real Postgres: V2 applies, the entity validates against it, and
 *  the three queries behave. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TimelineEventRepositoryTest {

    private static final OffsetDateTime NIGHT = OffsetDateTime.of(2026, 10, 2, 1, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private TimelineEventRepository repository;

    private static TimelineEvent row(String language, int month, int day, int year, String country, String text) {
        return new TimelineEvent(null, language, (short) month, (short) day, year, country, text, NIGHT);
    }

    @Test
    void replacingADaySwapsThatLanguageAndDayOnly() {
        repository.saveAll(List.of(
                row("it", 8, 8, 1950, "IT", "vecchio"),
                row("en", 8, 8, 1950, "IT", "old"),
                row("it", 8, 9, 1950, "IT", "un altro giorno")));

        repository.replaceDay("it", MonthDay.of(8, 8), List.of(row("it", 8, 8, 1960, "IT", "nuovo")));

        assertThat(repository.findAll()).extracting(TimelineEvent::getText)
                .containsExactlyInAnyOrder("nuovo", "old", "un altro giorno");
    }

    @Test
    void replacingADayWithNothingClearsIt() {
        repository.save(row("it", 8, 8, 1950, "IT", "vecchio"));

        repository.replaceDay("it", MonthDay.of(8, 8), List.of());

        assertThat(repository.count()).isZero();
    }

    @Test
    void countriesAreCountedPerLanguage_inCodeOrder() {
        repository.saveAll(List.of(
                row("it", 1, 1, 1900, "IT", "a"),
                row("it", 1, 2, 1901, "IT", "b"),
                row("it", 1, 3, 1902, "FR", "c"),
                row("en", 1, 1, 1900, "DE", "d")));

        assertThat(repository.countByCountry("it"))
                .containsExactly(new CountryEventCount("FR", 1), new CountryEventCount("IT", 2));
        assertThat(repository.countByCountry("en")).containsExactly(new CountryEventCount("DE", 1));
    }

    @Test
    void aTimelineIsOneCountryInOneLanguage_withinTheYears_oldestFirst() {
        repository.saveAll(List.of(
                row("it", 12, 28, 1908, "IT", "terremoto"),
                row("it", 3, 1, 1908, "IT", "primavera"),
                row("it", 6, 2, 1946, "IT", "repubblica"),
                row("it", 1, 1, 1800, "IT", "troppo presto"),
                row("it", 1, 1, 1950, "FR", "altro paese"),
                row("en", 1, 1, 1950, "IT", "other language"),
                row("it", 3, 15, -44, "IT", "idi di marzo")));

        assertThat(repository.timeline("it", "IT", 1901, 2000)).extracting(TimelineEvent::getText)
                .containsExactly("primavera", "terremoto", "repubblica");
        assertThat(repository.timeline("it", "IT", Integer.MIN_VALUE, Integer.MAX_VALUE))
                .extracting(TimelineEvent::getText).first().isEqualTo("idi di marzo");
    }
}
