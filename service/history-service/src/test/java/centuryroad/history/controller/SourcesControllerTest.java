package centuryroad.history.controller;

import centuryroad.history.dto.IndexCoverage;
import centuryroad.history.repository.TimelineEventRepository;
import centuryroad.history.service.EditorialCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the project page can show about provenance: terms, how much there is, what is left out. */
@WebMvcTest(SourcesController.class)
@Import(EditorialCatalog.class)
@ActiveProfiles("test")
class SourcesControllerTest {

    private static final OffsetDateTime NIGHT = OffsetDateTime.of(2026, 10, 2, 1, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TimelineEventRepository repository;

    @Test
    void everySourceSaysItsTerms_andWhetherItMustBeCredited() throws Exception {
        given(repository.coverage()).willReturn(List.of());

        mvc.perform(get("/api/history/sources"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.data.sources[0].id").value("wikipedia-on-this-day"))
                .andExpect(jsonPath("$.data.sources[0].license").value("CC BY-SA 4.0"))
                .andExpect(jsonPath("$.data.sources[0].attributionRequired").value(true))
                .andExpect(jsonPath("$.data.sources[1].id").value("wikimedia-commons"))
                .andExpect(jsonPath("$.data.sources[1].license").value("Per file"))
                .andExpect(jsonPath("$.data.sources[2].id").value("natural-earth"))
                .andExpect(jsonPath("$.data.sources[2].attributionRequired").value(false));
    }

    @Test
    void theEditorialContentDeclaresNoLicence_becauseNoneWasChosen() throws Exception {
        given(repository.coverage()).willReturn(List.of());

        mvc.perform(get("/api/history/sources"))
                .andExpect(jsonPath("$.data.sources[3].id").value("century-road-editorial"))
                .andExpect(jsonPath("$.data.sources[3].license").doesNotExist())
                .andExpect(jsonPath("$.data.sources[3].url").doesNotExist());
    }

    @Test
    void theCoverageIsTheIndexAsItStandsAndTheEditorialCounts() throws Exception {
        given(repository.coverage()).willReturn(List.of(new IndexCoverage("it", 6800, 140, -3000, 2025, NIGHT)));

        mvc.perform(get("/api/history/sources"))
                .andExpect(jsonPath("$.data.coverage.index[0].language").value("it"))
                .andExpect(jsonPath("$.data.coverage.index[0].eventCount").value(6800))
                .andExpect(jsonPath("$.data.coverage.index[0].countryCount").value(140))
                .andExpect(jsonPath("$.data.coverage.index[0].oldestYear").value(-3000))
                .andExpect(jsonPath("$.data.coverage.index[0].newestYear").value(2025))
                .andExpect(jsonPath("$.data.coverage.index[0].indexedAt").value("2026-10-02T01:00:00Z"))
                .andExpect(jsonPath("$.data.coverage.editorial.paths").value(1))
                .andExpect(jsonPath("$.data.coverage.editorial.insights").value(6));
    }

    @Test
    void onlyAnInsightWithAReviewDateCountsAsReviewed_andTheLastDateIsShown() throws Exception {
        // test-b is the one fixture with a review date.
        given(repository.coverage()).willReturn(List.of());

        mvc.perform(get("/api/history/sources"))
                .andExpect(jsonPath("$.data.coverage.editorial.reviewedInsights").value(1))
                .andExpect(jsonPath("$.data.coverage.editorial.lastReviewedAt").value("2026-09-01"));
    }

    @Test
    void anEmptyIndexIsAnEmptyList_notAnError() throws Exception {
        given(repository.coverage()).willReturn(List.of());

        mvc.perform(get("/api/history/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.index").isEmpty());
    }

    @Test
    void theLimitsSayThatNothingIsVerifiedAndTheListIsNotComplete() throws Exception {
        given(repository.coverage()).willReturn(List.of());

        mvc.perform(get("/api/history/sources"))
                .andExpect(jsonPath("$.data.limits[?(@.code=='NOT_VERIFIED')].message",
                        org.hamcrest.Matchers.hasItem(containsString("non un'etichetta di affidabilità"))))
                .andExpect(jsonPath("$.data.limits[?(@.code=='NOT_A_COMPLETE_LIST')]").exists())
                .andExpect(jsonPath("$.data.limits[?(@.code=='ONLY_PLACED_EVENTS')]").exists());
    }
}
