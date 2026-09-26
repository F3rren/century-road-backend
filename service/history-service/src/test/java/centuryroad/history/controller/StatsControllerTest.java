package centuryroad.history.controller;

import centuryroad.history.model.CountryViewCounter;
import centuryroad.history.model.DayViewCounter;
import centuryroad.history.service.ViewStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract for reading back the aggregate counters: the envelope shape and how
 *  the limit query parameter reaches ViewStatsService, bounded to a sane range. */
@WebMvcTest(StatsController.class)
@ActiveProfiles("test")
class StatsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ViewStatsService viewStats;

    @Test
    void topDaysAreReturnedInTheEnvelope() throws Exception {
        given(viewStats.topDays(anyInt())).willReturn(List.of(new DayViewCounter((short) 10, (short) 16, 42)));

        mvc.perform(get("/api/history/stats/days"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].month").value(10))
                .andExpect(jsonPath("$.data[0].day").value(16))
                .andExpect(jsonPath("$.data[0].viewCount").value(42));

        verify(viewStats).topDays(10);
    }

    @Test
    void topCountriesAreReturnedInTheEnvelope() throws Exception {
        given(viewStats.topCountries(anyInt())).willReturn(List.of(new CountryViewCounter("IT", 7)));

        mvc.perform(get("/api/history/stats/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].countryCode").value("IT"))
                .andExpect(jsonPath("$.data[0].viewCount").value(7));

        verify(viewStats).topCountries(10);
    }

    @Test
    void aRequestedLimitIsPassedThrough() throws Exception {
        given(viewStats.topDays(anyInt())).willReturn(List.of());

        mvc.perform(get("/api/history/stats/days").param("limit", "3"));

        verify(viewStats).topDays(3);
    }

    @Test
    void aLimitAboveFiftyIsCappedAtFifty() throws Exception {
        given(viewStats.topDays(anyInt())).willReturn(List.of());

        mvc.perform(get("/api/history/stats/days").param("limit", "999"));

        verify(viewStats).topDays(50);
    }

    @Test
    void aLimitBelowOneIsRaisedToOne() throws Exception {
        given(viewStats.topDays(anyInt())).willReturn(List.of());

        mvc.perform(get("/api/history/stats/days").param("limit", "0"));

        verify(viewStats).topDays(1);
    }
}
