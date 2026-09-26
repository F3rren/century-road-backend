package centuryroad.history.controller;

import centuryroad.history.service.ViewStatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract for anonymous country-view tracking: which codes are accepted, which
 *  are rejected before ever reaching ViewStatsService, and what each answers. */
@WebMvcTest(TrackingController.class)
@ActiveProfiles("test")
class TrackingControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ViewStatsService viewStats;

    @Test
    void aValidCountryCodeIsAccepted_andRecorded() throws Exception {
        mvc.perform(post("/api/history/track/country/IT"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true));

        verify(viewStats).recordCountryView("IT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"it", "ITA", "I1", "12"})
    void anythingOtherThanTwoUppercaseLettersIsRejected_beforeRecordingAnything(String code) throws Exception {
        mvc.perform(post("/api/history/track/country/" + code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("INVALID_COUNTRY_CODE"));

        verifyNoInteractions(viewStats);
    }
}
