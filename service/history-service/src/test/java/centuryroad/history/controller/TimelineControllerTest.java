package centuryroad.history.controller;

import centuryroad.history.dto.CountryEventCount;
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of the country index: envelope shape, defaults, caching, and that a bad
 *  request is refused before the database is touched. */
@WebMvcTest(TimelineController.class)
@ActiveProfiles("test")
class TimelineControllerTest {

    private static final OffsetDateTime NIGHT = OffsetDateTime.of(2026, 10, 2, 1, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TimelineEventRepository repository;

    @Test
    void theCountriesComeWithTheirCounts_inItalianByDefault() throws Exception {
        given(repository.countByCountry("it")).willReturn(List.of(new CountryEventCount("IT", 12)));

        mvc.perform(get("/api/history/countries"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].countryCode").value("IT"))
                .andExpect(jsonPath("$.data[0].eventCount").value(12));
    }

    @Test
    void aTimelineCarriesItsEvents_theIndexTime_andTheLicence() throws Exception {
        given(repository.timeline("en", "IT", 1901, 2000)).willReturn(List.of(
                new TimelineEvent(1L, "en", (short) 12, (short) 28, 1908, "IT", "Messina earthquake.", NIGHT)));

        mvc.perform(get("/api/history/countries/IT/timeline")
                        .param("lang", "en").param("fromYear", "1901").param("toYear", "2000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.data.countryCode").value("IT"))
                .andExpect(jsonPath("$.data.language").value("en"))
                .andExpect(jsonPath("$.data.indexedAt").value("2026-10-02T01:00:00Z"))
                .andExpect(jsonPath("$.data.attribution.license").value("CC BY-SA 4.0"))
                .andExpect(jsonPath("$.data.events[0].year").value(1908))
                .andExpect(jsonPath("$.data.events[0].month").value(12))
                .andExpect(jsonPath("$.data.events[0].day").value(28))
                .andExpect(jsonPath("$.data.events[0].text").value("Messina earthquake."));
    }

    @Test
    void withoutYearsEveryYearIsAskedFor() throws Exception {
        given(repository.timeline(anyString(), anyString(), anyInt(), anyInt())).willReturn(List.of());

        mvc.perform(get("/api/history/countries/IT/timeline")).andExpect(status().isOk());

        verify(repository).timeline("it", "IT", Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    @Test
    void aCountryWithNothingIndexedIsAnEmptyList_notAnError() throws Exception {
        given(repository.timeline(anyString(), anyString(), anyInt(), anyInt())).willReturn(List.of());

        mvc.perform(get("/api/history/countries/AQ/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events").isEmpty())
                .andExpect(jsonPath("$.data.indexedAt").doesNotExist());
    }

    @Test
    void aMalformedCountryCodeIsRefused() throws Exception {
        for (String code : List.of("it", "ITA", "I1")) {
            mvc.perform(get("/api/history/countries/" + code + "/timeline"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_COUNTRY_CODE"));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void anUnsupportedLanguageIsRefused_onBothEndpoints() throws Exception {
        mvc.perform(get("/api/history/countries").param("lang", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_LANGUAGE"));
        mvc.perform(get("/api/history/countries/IT/timeline").param("lang", "fr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_LANGUAGE"));
        verifyNoInteractions(repository);
    }

    @Test
    void anImpossibleYearRangeIsRefused() throws Exception {
        mvc.perform(get("/api/history/countries/IT/timeline").param("fromYear", "2000").param("toYear", "1900"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_YEAR"));
        mvc.perform(get("/api/history/countries/IT/timeline").param("fromYear", "-10000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_YEAR"));
        verifyNoInteractions(repository);
    }
}
