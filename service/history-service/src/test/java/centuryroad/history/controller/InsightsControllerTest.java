package centuryroad.history.controller;

import centuryroad.history.service.EditorialCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of "Perché conta", over the small fixed content in
 *  src/test/resources/editorial-test. */
@WebMvcTest(InsightsController.class)
@Import(EditorialCatalog.class)
@ActiveProfiles("test")
class InsightsControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void everyInsightIsListedOldestFirst() throws Exception {
        mvc.perform(get("/api/history/insights"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].slug").value("test-a"))
                .andExpect(jsonPath("$.data[0].date.year").value(1901))
                .andExpect(jsonPath("$.data[0].date.precision").value("DAY"))
                .andExpect(jsonPath("$.data[5].slug").value("test-f"));
    }

    @Test
    void aDayListsItsInsights_whateverTheYear() throws Exception {
        // 5 March: 1901, 1950 and 2020 in the fixtures.
        mvc.perform(get("/api/history/insights").param("month", "3").param("day", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].date.year").value(1901))
                .andExpect(jsonPath("$.data[1].date.year").value(1950))
                .andExpect(jsonPath("$.data[2].date.year").value(2020));
    }

    @Test
    void aDateKnownOnlyByItsMonthSaysSo_andBelongsToNoDay() throws Exception {
        // test-d is November 1989, written as the 1st: that placeholder day must not find it.
        mvc.perform(get("/api/history/insights"))
                .andExpect(jsonPath("$.data[3].slug").value("test-d"))
                .andExpect(jsonPath("$.data[3].date.precision").value("MONTH"));
        mvc.perform(get("/api/history/insights/test-d"))
                .andExpect(jsonPath("$.data.date.precision").value("MONTH"));
        mvc.perform(get("/api/history/insights").param("month", "11").param("day", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void aDayWithNoInsightIsAnEmptyList_notAnError() throws Exception {
        mvc.perform(get("/api/history/insights").param("month", "12").param("day", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void monthAndDayGoTogether_andMustBeARealDay() throws Exception {
        mvc.perform(get("/api/history/insights").param("month", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_DATE"));
        mvc.perform(get("/api/history/insights").param("day", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_DATE"));
        mvc.perform(get("/api/history/insights").param("month", "2").param("day", "30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_DATE"));
    }

    @Test
    void anInsightIsToldInThreeParts_withWhereToReadNext_andWhereItCameFrom() throws Exception {
        mvc.perform(get("/api/history/insights/test-a"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.data.slug").value("test-a"))
                .andExpect(jsonPath("$.data.language").value("it"))
                .andExpect(jsonPath("$.data.before").value("Prima uno due tre."))
                .andExpect(jsonPath("$.data.event").value("Evento uno due tre."))
                .andExpect(jsonPath("$.data.after").value("Dopo uno due tre."))
                .andExpect(jsonPath("$.data.related.length()").value(2))
                .andExpect(jsonPath("$.data.related[0].slug").value("test-b"))
                .andExpect(jsonPath("$.data.related[0].title").value("Titolo test-b"))
                .andExpect(jsonPath("$.data.related[0].date.year").value(1950))
                .andExpect(jsonPath("$.data.related[0].reason").value("Perché sì."))
                .andExpect(jsonPath("$.data.inPaths[0].path").value("test-path"))
                .andExpect(jsonPath("$.data.inPaths[0].position").value(1))
                .andExpect(jsonPath("$.data.inPaths[0].stopCount").value(6))
                .andExpect(jsonPath("$.data.sources[0].publisher").value("Wikipedia"))
                .andExpect(jsonPath("$.data.sources[0].license").value("CC BY-SA 4.0"))
                .andExpect(jsonPath("$.data.notes[0]").value("Una nota."))
                .andExpect(jsonPath("$.data.provenance.author").value("Century Road"));
    }

    @Test
    void aReviewDateAppearsOnlyWhereOneExists() throws Exception {
        mvc.perform(get("/api/history/insights/test-a"))
                .andExpect(jsonPath("$.data.provenance.reviewedAt").doesNotExist());
        mvc.perform(get("/api/history/insights/test-b"))
                .andExpect(jsonPath("$.data.provenance.reviewedAt").value("2026-09-01"));
    }

    @Test
    void anApproximatePlaceCarriesItsNote() throws Exception {
        mvc.perform(get("/api/history/insights/test-c"))
                .andExpect(jsonPath("$.data.place.approximate").value(true))
                .andExpect(jsonPath("$.data.place.note").value("Il punto sulla mappa è un luogo di partenza."));
        mvc.perform(get("/api/history/insights/test-a"))
                .andExpect(jsonPath("$.data.place.approximate").value(false))
                .andExpect(jsonPath("$.data.place.note").doesNotExist());
    }

    @Test
    void anUnknownInsightIsA404_withItsOwnCode() throws Exception {
        mvc.perform(get("/api/history/insights/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("INSIGHT_NOT_FOUND"))
                .andExpect(jsonPath("$.userMessage").value("Approfondimento non trovato."));
    }
}
