package centuryroad.history.controller;

import centuryroad.history.TestClock;
import centuryroad.history.config.HistoryProperties;
import centuryroad.history.model.ErrorReport;
import centuryroad.history.repository.ErrorReportRepository;
import centuryroad.history.service.EditorialCatalog;
import centuryroad.history.service.ReportRateLimiter;
import centuryroad.history.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of "Segnala un errore", with the real service behind it and the database
 *  replaced: what is accepted, how each refusal is answered, and the 429. */
@WebMvcTest(ReportsController.class)
@Import({ReportService.class, ReportRateLimiter.class, EditorialCatalog.class,
        ReportsControllerTest.FixedClock.class})
@ActiveProfiles("test")
class ReportsControllerTest {

    // A web slice does not scan configuration properties, and the limiter reads its limits from them.
    @TestConfiguration
    @EnableConfigurationProperties(HistoryProperties.class)
    static class FixedClock {
        @Bean
        Clock clock() {
            return TestClock.atNoon();
        }
    }

    private static final String INSIGHT_REPORT = """
            {"target":{"type":"INSIGHT","slug":"test-a"},"category":"WRONG_TEXT",
             "message":"La data è sbagliata: il lancio fu il 4 ottobre."}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Clock clock;

    @MockBean
    private ErrorReportRepository repository;

    @BeforeEach
    void saving() {
        // The limiter lives as long as the context, so a report sent by one test would count in
        // the next. Letting the window pass is what starts everybody afresh.
        ((TestClock) clock).advance(Duration.ofHours(2));
        given(repository.save(any(ErrorReport.class))).willAnswer(call -> {
            ErrorReport r = call.getArgument(0);
            return new ErrorReport(7L, r.getTargetType(), r.getTargetSlug(), r.getTargetYear(), r.getTargetMonth(),
                    r.getTargetDay(), r.getTargetLanguage(), r.getTargetText(), r.getCategory(), r.getMessage(),
                    r.getContact(), r.getCreatedAt(), null);
        });
    }

    @Test
    void aReportIsStored_andAnswersCreatedWithItsNumber() throws Exception {
        mvc.perform(post("/api/history/reports").contentType(MediaType.APPLICATION_JSON).content(INSIGHT_REPORT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.receivedAt").isNotEmpty());
    }

    @Test
    void anEventIsReportedByItsDate() throws Exception {
        mvc.perform(post("/api/history/reports").contentType(MediaType.APPLICATION_JSON).content("""
                        {"target":{"type":"EVENT","year":1957,"month":10,"day":4,"language":"it",
                                   "text":"Viene lanciato lo Sputnik 1."},
                         "category":"WRONG_DATE","message":"A Baikonur era già il 5 ottobre.","contact":"nome@example.org"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void aMalformedReportIsA400_withItsOwnCode() throws Exception {
        mvc.perform(post("/api/history/reports").contentType(MediaType.APPLICATION_JSON).content("""
                        {"target":{"type":"INSIGHT","slug":"ghost"},"category":"OTHER","message":"Un messaggio lungo abbastanza."}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("INVALID_REPORT"))
                .andExpect(jsonPath("$.userMessage").value("Non trovo l'elemento che vuoi segnalare."));
        verifyNoInteractions(repository);
    }

    @Test
    void aBodyThatIsNotThisShapeIsA400_notA500() throws Exception {
        for (String body : new String[]{"", "not json", "{\"category\":\"NOT_A_CATEGORY\"}",
                "{\"target\":{\"type\":\"SOMETHING\"}}"}) {
            mvc.perform(post("/api/history/reports").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void aBodyOverTheCapIsRefusedUnread_evenIfItIsValidJson() throws Exception {
        String huge = "{\"target\":{\"type\":\"INSIGHT\",\"slug\":\"test-a\"},\"category\":\"OTHER\","
                + "\"message\":\"" + "x".repeat(ReportsController.MAX_BODY_BYTES) + "\"}";

        mvc.perform(post("/api/history/reports").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.userMessage").value("La segnalazione è troppo lunga."));
        verifyNoInteractions(repository);
    }

    @Test
    void onlyJsonIsAccepted_soAPlainTextPostFromAnotherSiteIsRefused() throws Exception {
        mvc.perform(post("/api/history/reports").contentType(MediaType.TEXT_PLAIN).content(INSIGHT_REPORT))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(repository);
    }

    @Test
    void theLimitIsAnswered429_withRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/history/reports").contentType(MediaType.APPLICATION_JSON).content(INSIGHT_REPORT))
                    .andExpect(status().isCreated());
        }

        mvc.perform(post("/api/history/reports").contentType(MediaType.APPLICATION_JSON).content(INSIGHT_REPORT))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.userMessage").value("Hai inviato troppe segnalazioni. Riprova più tardi."));
    }

    @Test
    void theEndpointOnlyTakesPost() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/history/reports"))
                .andExpect(status().isMethodNotAllowed());
    }
}
