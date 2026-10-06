package centuryroad.history.dto;

import centuryroad.history.model.TimelineEvent;
import centuryroad.history.query.SamePeriodQuery;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of the "same period" answer that are not HTTP: what is kept when a country has
 *  more events than are shown, how countries are ordered, and when the data is called thin. */
class SamePeriodResponseUnitTest {

    private static final OffsetDateTime NIGHT = OffsetDateTime.of(2026, 10, 2, 1, 0, 0, 0, ZoneOffset.UTC);

    private static TimelineEvent row(String country, int year, String text) {
        return new TimelineEvent(null, "it", (short) 1, (short) 1, year, country, text, NIGHT);
    }

    private static SamePeriodQuery query(int year, int span, String exclude, int perCountry) {
        return SamePeriodQuery.of(year, span, "it", exclude, perCountry);
    }

    @Test
    void whenACountryHasMoreThanAreShown_theOnesClosestToTheYearAreKept_andShownOldestFirst() {
        List<TimelineEvent> rows = List.of(
                row("FR", 1964, "lontano"), row("FR", 1968, "vicino"), row("FR", 1969, "esatto"),
                row("FR", 1970, "vicino dopo"), row("FR", 1974, "lontano dopo"));

        var response = SamePeriodResponse.from(query(1969, 5, null, 3), rows);

        var france = response.countries().get(0);
        assertThat(france.eventCount()).isEqualTo(5);
        assertThat(france.events()).extracting(TimelineResponse.Event::text)
                .containsExactly("vicino", "esatto", "vicino dopo");
    }

    @Test
    void countriesComeWithTheMostEventsFirst_thenByCode() {
        List<TimelineEvent> rows = new ArrayList<>(List.of(
                row("AT", 1969, "a"), row("DE", 1969, "b"), row("DE", 1970, "c"), row("BR", 1969, "d"),
                row("BR", 1970, "e")));

        var response = SamePeriodResponse.from(query(1969, 5, null, 3), rows);

        assertThat(response.countries()).extracting(SamePeriodResponse.CountryEvents::countryCode)
                .containsExactly("BR", "DE", "AT");
    }

    @Test
    void theExcludedCountryCountsForNothing_notEvenInTheCoverage() {
        List<TimelineEvent> rows = List.of(row("IT", 1969, "a"), row("IT", 1970, "b"), row("US", 1969, "c"));

        var response = SamePeriodResponse.from(query(1969, 5, "IT", 3), rows);

        assertThat(response.coverage().eventCount()).isEqualTo(1);
        assertThat(response.coverage().countryCount()).isEqualTo(1);
        assertThat(response.excludedCountry()).isEqualTo("IT");
    }

    @Test
    void coverageIsNone_withNothing() {
        var response = SamePeriodResponse.from(query(1969, 5, null, 3), List.of());

        assertThat(response.coverage().level()).isEqualTo(SamePeriodResponse.CoverageLevel.NONE);
        assertThat(response.coverage().eventCount()).isZero();
        assertThat(response.countries()).isEmpty();
    }

    @Test
    void coverageIsSparse_belowFiveEvents_orBelowThreeCountries_andOkOtherwise() {
        // 4 events in 4 countries: too few events.
        assertThat(level(List.of(row("A1", 1969, "a"), row("B1", 1969, "b"), row("C1", 1969, "c"),
                row("D1", 1969, "d")))).isEqualTo(SamePeriodResponse.CoverageLevel.SPARSE);
        // 6 events in 2 countries: too few countries.
        assertThat(level(List.of(row("AA", 1969, "a"), row("AA", 1969, "b"), row("AA", 1969, "c"),
                row("BB", 1969, "d"), row("BB", 1969, "e"), row("BB", 1969, "f"))))
                .isEqualTo(SamePeriodResponse.CoverageLevel.SPARSE);
        // 5 events in 3 countries: enough.
        assertThat(level(List.of(row("AA", 1969, "a"), row("BB", 1969, "b"), row("CC", 1969, "c"),
                row("CC", 1969, "d"), row("CC", 1969, "e")))).isEqualTo(SamePeriodResponse.CoverageLevel.OK);
    }

    @Test
    void theNoteSaysTheIndexIsASelection_atEveryLevel() {
        var ok = SamePeriodResponse.from(query(1969, 5, null, 3), List.of(row("AA", 1969, "a"), row("BB", 1969, "b"),
                row("CC", 1969, "c"), row("CC", 1969, "d"), row("CC", 1969, "e")));
        var none = SamePeriodResponse.from(query(1969, 5, null, 3), List.of());

        assertThat(ok.coverage().note()).contains("non è tutto ciò che accadde nel mondo");
        assertThat(none.coverage().note()).contains("non è tutto ciò che accadde nel mondo");
    }

    @Test
    void theWindowIsClampedToTheYearsTheApiAccepts() {
        var query = SamePeriodQuery.of(9998, 5, "it", null, 3);

        assertThat(query.fromYear()).isEqualTo(9993);
        assertThat(query.toYear()).isEqualTo(9999);
    }

    private static SamePeriodResponse.CoverageLevel level(List<TimelineEvent> rows) {
        return SamePeriodResponse.from(query(1969, 5, null, 3), rows).coverage().level();
    }
}
