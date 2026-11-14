package centuryroad.history.service;

import centuryroad.history.dto.CountryEventCount;
import centuryroad.history.model.Language;
import centuryroad.history.model.TimelineEvent;
import centuryroad.history.query.RandomEventQuery;
import centuryroad.history.query.SamePeriodQuery;
import centuryroad.history.query.TimelineQuery;
import centuryroad.history.repository.TimelineEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * What a question about the country index means in terms of the table: which query answers it, and what it
 * is given. The controllers' own tests, which mock the repository under the real service, cover the HTTP side.
 */
class TimelineServiceUnitTest {

    private final TimelineEventRepository repository = mock(TimelineEventRepository.class);
    private final TimelineService service = new TimelineService(repository);

    @Test
    void aRandomEventWithNoCountryIsDrawnFromEverywhere() {
        TimelineEvent drawn = mock(TimelineEvent.class);
        when(repository.randomEvent("it", 1900, 1999)).thenReturn(Optional.of(drawn));

        Optional<TimelineEvent> result = service.randomEvent(RandomEventQuery.of("it", null, 1900, 1999));

        assertThat(result).containsSame(drawn);
        verify(repository).randomEvent("it", 1900, 1999);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aRandomEventWithACountryIsDrawnFromThatCountryOnly() {
        TimelineEvent drawn = mock(TimelineEvent.class);
        when(repository.randomEventIn("en", "IT", 1900, 1999)).thenReturn(Optional.of(drawn));

        Optional<TimelineEvent> result = service.randomEvent(RandomEventQuery.of("en", "IT", 1900, 1999));

        assertThat(result).containsSame(drawn);
        verify(repository).randomEventIn("en", "IT", 1900, 1999);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void theRepositoryIsAskedInTheEditionsCode_andWithTheYearsOfTheQuery() {
        List<TimelineEvent> rows = List.of(mock(TimelineEvent.class));
        when(repository.timeline("it", "FR", 1789, 1799)).thenReturn(rows);
        when(repository.inYears("en", 1960, 1970)).thenReturn(rows);

        assertThat(service.timeline(TimelineQuery.of("FR", "it", 1789, 1799))).isSameAs(rows);
        assertThat(service.inYears(SamePeriodQuery.of(1965, 5, "en", null, null))).isSameAs(rows);
    }

    @Test
    void theCountriesAreThoseOfTheEdition() {
        List<CountryEventCount> counts = List.of(new CountryEventCount("IT", 3));
        when(repository.countByCountry(Language.IT.code())).thenReturn(counts);

        assertThat(service.countriesWithEvents(Language.IT)).isSameAs(counts);
    }
}
