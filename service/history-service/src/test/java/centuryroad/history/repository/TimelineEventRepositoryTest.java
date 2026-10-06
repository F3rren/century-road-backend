package centuryroad.history.repository;

import centuryroad.history.TestcontainersConfiguration;
import centuryroad.history.dto.CountryEventCount;
import centuryroad.history.dto.IndexCoverage;
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
 *  its queries behave: the timeline, the random pick, the window of years and the coverage. */
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

    @Test
    void aRandomEventIsOneOfThoseThatMatch_languageAndYears_andNothingWhenNoneDoes() {
        repository.saveAll(List.of(
                row("it", 1, 1, 1950, "IT", "il solo"),
                row("it", 1, 2, 1850, "IT", "troppo presto"),
                row("en", 1, 3, 1950, "IT", "other language")));

        // Several draws: with a single match, every one of them must be it.
        for (int i = 0; i < 5; i++) {
            assertThat(repository.randomEvent("it", 1900, 2000)).get().extracting(TimelineEvent::getText)
                    .isEqualTo("il solo");
        }
        assertThat(repository.randomEvent("it", 1960, 2000)).isEmpty();
        assertThat(repository.randomEvent("fr", Integer.MIN_VALUE, Integer.MAX_VALUE)).isEmpty();
    }

    @Test
    void aRandomEventInACountryIsFromThatCountry() {
        repository.saveAll(List.of(
                row("it", 1, 1, 1950, "IT", "italiano"),
                row("it", 1, 2, 1951, "FR", "francese"),
                row("it", 1, 3, 1952, "FR", "ancora francese")));

        for (int i = 0; i < 10; i++) {
            assertThat(repository.randomEventIn("it", "IT", Integer.MIN_VALUE, Integer.MAX_VALUE)).get()
                    .extracting(TimelineEvent::getCountryCode).isEqualTo("IT");
        }
        assertThat(repository.randomEventIn("it", "DE", Integer.MIN_VALUE, Integer.MAX_VALUE)).isEmpty();
        assertThat(repository.randomEventIn("it", "FR", 1952, 1952)).get().extracting(TimelineEvent::getText)
                .isEqualTo("ancora francese");
    }

    @Test
    void aWindowOfYearsHoldsOneLanguageInclusive_byCountryThenDate() {
        repository.saveAll(List.of(
                row("it", 6, 1, 1969, "US", "us tarda"),
                row("it", 3, 1, 1969, "US", "us presto"),
                row("it", 1, 1, 1964, "FR", "fr estremo"),
                row("it", 1, 1, 1974, "FR", "fr altro estremo"),
                row("it", 1, 1, 1963, "FR", "fuori"),
                row("it", 1, 1, 1975, "FR", "fuori anche questo"),
                row("en", 1, 1, 1969, "US", "other language")));

        assertThat(repository.inYears("it", 1964, 1974)).extracting(TimelineEvent::getText)
                .containsExactly("fr estremo", "fr altro estremo", "us presto", "us tarda");
    }

    @Test
    void theCoverageIsPerLanguage_withCountriesYearsAndTheNewestWrite() {
        OffsetDateTime later = NIGHT.plusDays(1);
        repository.saveAll(List.of(
                row("it", 1, 1, 1900, "IT", "a"),
                row("it", 1, 2, 1950, "IT", "b"),
                row("it", 1, 3, -44, "IT", "c"),
                new TimelineEvent(null, "it", (short) 1, (short) 4, 2000, "FR", "d", later),
                row("en", 1, 1, 1969, "US", "e")));

        assertThat(repository.coverage()).containsExactly(
                new IndexCoverage("en", 1, 1, 1969, 1969, NIGHT),
                new IndexCoverage("it", 4, 2, -44, 2000, later));
    }

    @Test
    void theCoverageOfAnEmptyIndexIsNothing() {
        assertThat(repository.coverage()).isEmpty();
    }
}
