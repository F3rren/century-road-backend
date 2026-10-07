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

/** The HTTP contract of the paths and of "Inizia da qui", over the small fixed content in
 *  src/test/resources/editorial-test (six insights, one path, two proposals). */
@WebMvcTest(PathsController.class)
@Import(EditorialCatalog.class)
@ActiveProfiles("test")
class PathsControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void startHereListsTheProposals_aPathWithItsCard_anInsightWithItsDate() throws Exception {
        mvc.perform(get("/api/history/start-here"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("PATH"))
                .andExpect(jsonPath("$.data[0].slug").value("test-path"))
                .andExpect(jsonPath("$.data[0].title").value("Percorso di prova"))
                .andExpect(jsonPath("$.data[0].teaser").value("Comincia dal percorso."))
                .andExpect(jsonPath("$.data[0].path.stopCount").value(6))
                .andExpect(jsonPath("$.data[0].insight").doesNotExist())
                .andExpect(jsonPath("$.data[1].type").value("INSIGHT"))
                .andExpect(jsonPath("$.data[1].insight.date.year").value(1969))
                .andExpect(jsonPath("$.data[1].path").doesNotExist());
    }

    @Test
    void thePathsAreCards_withTopicYearsReadingTimeAndStopCount_andNoCoverWhenThereIsNone() throws Exception {
        mvc.perform(get("/api/history/paths"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slug").value("test-path"))
                .andExpect(jsonPath("$.data[0].tagline").value("Una frase."))
                .andExpect(jsonPath("$.data[0].topic").value("ETA_MODERNA"))
                .andExpect(jsonPath("$.data[0].topicLabel").value("Assolutismo, Lumi e rivoluzioni"))
                .andExpect(jsonPath("$.data[0].startYear").value(1901))
                .andExpect(jsonPath("$.data[0].endYear").value(2020))
                .andExpect(jsonPath("$.data[0].readingMinutes").value(1))
                .andExpect(jsonPath("$.data[0].stopCount").value(6))
                .andExpect(jsonPath("$.data[0].cover").doesNotExist());
    }

    @Test
    void aPathHasItsStopsInOrder_eachWithPlaceDateAndNarrative() throws Exception {
        mvc.perform(get("/api/history/paths/test-path"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.data.language").value("it"))
                .andExpect(jsonPath("$.data.intro").value("Una breve introduzione di prova."))
                .andExpect(jsonPath("$.data.topic").value("ETA_MODERNA"))
                .andExpect(jsonPath("$.data.topicLabel").value("Assolutismo, Lumi e rivoluzioni"))
                .andExpect(jsonPath("$.data.startYear").value(1901))
                .andExpect(jsonPath("$.data.endYear").value(2020))
                .andExpect(jsonPath("$.data.stops.length()").value(6))
                .andExpect(jsonPath("$.data.stops[0].position").value(1))
                .andExpect(jsonPath("$.data.stops[0].slug").value("test-a"))
                .andExpect(jsonPath("$.data.stops[0].narrative").value("Perché test-a viene qui."))
                .andExpect(jsonPath("$.data.stops[0].place.lat").value(10.0))
                .andExpect(jsonPath("$.data.stops[0].place.approximate").value(false))
                .andExpect(jsonPath("$.data.stops[0].date.year").value(1901))
                .andExpect(jsonPath("$.data.stops[2].slug").value("test-c"))
                .andExpect(jsonPath("$.data.stops[2].place.approximate").value(true))
                .andExpect(jsonPath("$.data.stops[2].place.note").value("Il punto sulla mappa è un luogo di partenza."))
                .andExpect(jsonPath("$.data.stops[5].position").value(6));
    }

    @Test
    void anUnknownPathIsA404_withItsOwnCode() throws Exception {
        mvc.perform(get("/api/history/paths/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("PATH_NOT_FOUND"))
                .andExpect(jsonPath("$.userMessage").value("Percorso non trovato."));
    }
}
