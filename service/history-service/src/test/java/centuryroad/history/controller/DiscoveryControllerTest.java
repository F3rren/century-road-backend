package centuryroad.history.controller;

import centuryroad.history.model.TimelineEvent;
import centuryroad.history.repository.TimelineEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of "Sorprendimi" and "Nello stesso periodo": filters, caching, the
 *  honest empty answers, and that a bad request is refused before the database is touched. */
@WebMvcTest(DiscoveryController.class)
@ActiveProfiles("test")
class DiscoveryControllerTest {

    private static final OffsetDateTime NIGHT = OffsetDateTime.of(2026, 10, 2, 1, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TimelineEventRepository repository;

    private static TimelineEvent row(String country, int year, int month, int day, String text) {
        return new TimelineEvent(null, "it", (short) month, (short) day, year, country, text, NIGHT);
    }

    // ---- random --------------------------------------------------------------------------------

    @Test
    void aRandomEventCarriesItsDateItsCountryAndTheLicence_andIsNeverCached() throws Exception {
        given(repository.randomEvent("it", Integer.MIN_VALUE, Integer.MAX_VALUE))
                .willReturn(Optional.of(row("IT", 1908, 12, 28, "Terremoto di Messina.")));

        mvc.perform(get("/api/history/random"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.language").value("it"))
                .andExpect(jsonPath("$.data.event.year").value(1908))
                .andExpect(jsonPath("$.data.event.month").value(12))
                .andExpect(jsonPath("$.data.event.day").value(28))
                .andExpect(jsonPath("$.data.event.countryCode").value("IT"))
                .andExpect(jsonPath("$.data.event.text").value("Terremoto di Messina."))
                .andExpect(jsonPath("$.data.attribution.license").value("CC BY-SA 4.0"));
    }

    @Test
    void withACountryTheCountryQueryIsUsed_withTheYearsAsFilters() throws Exception {
        given(repository.randomEventIn("en", "JP", 1900, 1999))
                .willReturn(Optional.of(row("JP", 1923, 9, 1, "Great Kanto earthquake.")));

        mvc.perform(get("/api/history/random").param("lang", "en").param("country", "JP")
                        .param("fromYear", "1900").param("toYear", "1999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.countryCode").value("JP"));

        verify(repository).randomEventIn("en", "JP", 1900, 1999);
    }

    @Test
    void whenNothingMatchesTheAnswerIsA200WithNoEvent_notAn404() throws Exception {
        given(repository.randomEventIn(anyString(), anyString(), anyInt(), anyInt())).willReturn(Optional.empty());

        mvc.perform(get("/api/history/random").param("country", "AQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.event").doesNotExist())
                .andExpect(jsonPath("$.data.attribution.source").value("Wikipedia"));
    }

    @Test
    void aBadFilterIsRefusedBeforeTheDatabaseIsTouched() throws Exception {
        mvc.perform(get("/api/history/random").param("country", "it"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_COUNTRY_CODE"));
        mvc.perform(get("/api/history/random").param("lang", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_LANGUAGE"));
        mvc.perform(get("/api/history/random").param("fromYear", "2000").param("toYear", "1900"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_YEAR"));
        verifyNoInteractions(repository);
    }

    // ---- same period ---------------------------------------------------------------------------

    @Test
    void theSamePeriodIsAWindowAroundTheYear_withDefaults_andSaysItIsATemporalComparison() throws Exception {
        given(repository.inYears("it", 1964, 1974)).willReturn(List.of(
                row("FR", 1968, 5, 30, "Maggio francese."),
                row("FR", 1970, 1, 1, "Altro."),
                row("JP", 1964, 10, 10, "Olimpiadi di Tokyo."),
                row("US", 1969, 7, 20, "Allunaggio.")));

        mvc.perform(get("/api/history/same-period").param("year", "1969"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.data.year").value(1969))
                .andExpect(jsonPath("$.data.fromYear").value(1964))
                .andExpect(jsonPath("$.data.toYear").value(1974))
                .andExpect(jsonPath("$.data.comparison").value("TEMPORAL"))
                .andExpect(jsonPath("$.data.notice").value(
                        "Eventi avvenuti negli stessi anni in paesi diversi: un confronto nel tempo, non una catena di cause ed effetti."))
                .andExpect(jsonPath("$.data.coverage.level").value("SPARSE"))
                .andExpect(jsonPath("$.data.coverage.eventCount").value(4))
                .andExpect(jsonPath("$.data.coverage.countryCount").value(3))
                .andExpect(jsonPath("$.data.countries[0].countryCode").value("FR"))
                .andExpect(jsonPath("$.data.countries[0].eventCount").value(2))
                .andExpect(jsonPath("$.data.countries[0].events[0].text").value("Maggio francese."))
                .andExpect(jsonPath("$.data.attribution.license").value("CC BY-SA 4.0"));
    }

    @Test
    void theExcludedCountryIsLeftOut_andNamedInTheAnswer() throws Exception {
        given(repository.inYears("it", 1964, 1974)).willReturn(List.of(
                row("IT", 1966, 11, 4, "Alluvione di Firenze."),
                row("US", 1969, 7, 20, "Allunaggio.")));

        mvc.perform(get("/api/history/same-period").param("year", "1969").param("excludeCountry", "IT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excludedCountry").value("IT"))
                .andExpect(jsonPath("$.data.countries.length()").value(1))
                .andExpect(jsonPath("$.data.countries[0].countryCode").value("US"));
    }

    @Test
    void anEmptyWindowIsAnHonestNone_notAnError() throws Exception {
        given(repository.inYears(anyString(), anyInt(), anyInt())).willReturn(List.of());

        mvc.perform(get("/api/history/same-period").param("year", "-3000").param("span", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.countries").isEmpty())
                .andExpect(jsonPath("$.data.coverage.level").value("NONE"))
                .andExpect(jsonPath("$.data.coverage.note").value(org.hamcrest.Matchers.containsString("Nell'indice non ci sono eventi")));
    }

    @Test
    void theWindowAndTheLanguageAreWhatWasAskedFor() throws Exception {
        given(repository.inYears(anyString(), anyInt(), anyInt())).willReturn(List.of());

        mvc.perform(get("/api/history/same-period").param("year", "1900").param("span", "2").param("lang", "en"))
                .andExpect(status().isOk());

        verify(repository).inYears("en", 1898, 1902);
    }

    @Test
    void aBadRequestIsRefusedBeforeTheDatabaseIsTouched() throws Exception {
        mvc.perform(get("/api/history/same-period"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        mvc.perform(get("/api/history/same-period").param("year", "10000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_YEAR"));
        mvc.perform(get("/api/history/same-period").param("year", "1969").param("span", "26"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SPAN"));
        mvc.perform(get("/api/history/same-period").param("year", "1969").param("span", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SPAN"));
        mvc.perform(get("/api/history/same-period").param("year", "1969").param("perCountry", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_LIMIT"));
        mvc.perform(get("/api/history/same-period").param("year", "1969").param("perCountry", "11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_LIMIT"));
        mvc.perform(get("/api/history/same-period").param("year", "1969").param("excludeCountry", "ita"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_COUNTRY_CODE"));
        mvc.perform(get("/api/history/same-period").param("year", "1969").param("lang", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_LANGUAGE"));
        verifyNoInteractions(repository);
    }
}
