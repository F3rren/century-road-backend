package centuryroad.history.controller;

import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.exception.UpstreamRateLimitedException;
import centuryroad.history.exception.UpstreamUnavailableException;
import centuryroad.history.model.Coordinates;
import centuryroad.history.model.Entry;
import centuryroad.history.model.ImageRef;
import centuryroad.history.model.Language;
import centuryroad.history.model.OnThisDayResult;
import centuryroad.history.model.PageRef;
import centuryroad.history.model.Section;
import centuryroad.history.model.SectionResult;
import centuryroad.history.query.OnThisDayQuery;
import centuryroad.history.query.YearRange;
import centuryroad.history.service.OnThisDayService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.MonthDay;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract: what a frontend sees, in the success case and in every failure. The
 * service is mocked, so this is about status codes, the envelope, headers and how query
 * parameters reach the service - not about where the data comes from.
 */
@WebMvcTest(OnThisDayController.class)
@ActiveProfiles("test")
class OnThisDayControllerTest {

    private static final String URL = "/api/history/on-this-day/10/16";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private OnThisDayService service;

    private static OnThisDayResult result(List<String> warnings, boolean stale) {
        PageRef page = new PageRef("Papa Esempio", "papa", "Un papa.", "https://it.wikipedia.org/wiki/Papa_Esempio",
                null, null, null, null);
        PageRef rich = new PageRef("Beirut", "capitale del Libano", "Beirut e' la capitale.", "https://it.wikipedia.org/wiki/Beirut",
                new ImageRef("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Beirut.jpg/330px-Beirut.jpg", 330, 220,
                        "https://commons.wikimedia.org/wiki/File:Beirut.jpg"),
                new ImageRef("https://upload.wikimedia.org/wikipedia/commons/a/ab/Beirut.jpg", 3000, 2000,
                        "https://commons.wikimedia.org/wiki/File:Beirut.jpg"),
                new Coordinates(33.8938, 35.5018), "Q3820");
        Map<Section, SectionResult> sections = new LinkedHashMap<>();
        sections.put(Section.EVENTS, new SectionResult(Language.IT, false, stale,
                List.of(new Entry("Un evento.", 1978, List.of(page, rich)))));
        sections.put(Section.BIRTHS, new SectionResult(Language.EN, true, false,
                List.of(new Entry("A birth.", 1351, List.of()))));
        sections.put(Section.HOLIDAYS, new SectionResult(Language.IT, false, false,
                List.of(new Entry("Una festa.", null, List.of()))));
        return new OnThisDayResult(MonthDay.of(10, 16), Language.IT, sections, warnings);
    }

    private OnThisDayQuery queryPassedToTheService() {
        ArgumentCaptor<OnThisDayQuery> captor = ArgumentCaptor.forClass(OnThisDayQuery.class);
        verify(service).find(captor.capture());
        return captor.getValue();
    }

    // ----- success ----------------------------------------------------------------------

    @Test
    void aGoodRequestIsAnsweredInTheEnvelope() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.date.month").value(10))
                .andExpect(jsonPath("$.data.date.day").value(16))
                .andExpect(jsonPath("$.data.language").value("it"))
                .andExpect(jsonPath("$.data.warnings").isEmpty());
    }

    @Test
    void eachSectionSaysWhichLanguageItCameFromAndWhetherThatWasAFallback() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL))
                .andExpect(jsonPath("$.data.sections.events.language").value("it"))
                .andExpect(jsonPath("$.data.sections.events.fallback").value(false))
                .andExpect(jsonPath("$.data.sections.births.language").value("en"))
                .andExpect(jsonPath("$.data.sections.births.fallback").value(true))
                .andExpect(jsonPath("$.data.sections.events.items[0].text").value("Un evento."))
                .andExpect(jsonPath("$.data.sections.events.items[0].year").value(1978))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[0].url")
                        .value("https://it.wikipedia.org/wiki/Papa_Esempio"));
    }

    @Test
    void aHolidayCarriesNoYearKeyAtAll_andAPageWithoutAThumbnailNoThumbnailKey() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL))
                .andExpect(jsonPath("$.data.sections.holidays.items[0].year").doesNotExist())
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[0].thumbnail").doesNotExist());
    }

    @Test
    void aPageCarriesItsOriginalImageCoordinatesAndWikidataIdUnderTheseNames() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[1].thumbnail.width").value(330))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[1].originalImage.url")
                        .value("https://upload.wikimedia.org/wikipedia/commons/a/ab/Beirut.jpg"))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[1].originalImage.width").value(3000))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[1].originalImage.filePageUrl")
                        .value("https://commons.wikimedia.org/wiki/File:Beirut.jpg"))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[1].coordinates.lat").value(33.8938))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[1].coordinates.lon").value(35.5018))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[1].wikibaseItem").value("Q3820"));
    }

    @Test
    void aPageWithoutThemHasNoSuchKeysRatherThanNullOnes() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL))
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[0].originalImage").doesNotExist())
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[0].coordinates").doesNotExist())
                .andExpect(jsonPath("$.data.sections.events.items[0].pages[0].wikibaseItem").doesNotExist());
    }

    @Test
    void theSectionsAppearInTheOrderTheServiceBuiltThem() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        String body = mvc.perform(get(URL)).andReturn().getResponse().getContentAsString();

        assertThat(body.indexOf("\"events\"")).isLessThan(body.indexOf("\"births\""));
        assertThat(body.indexOf("\"births\"")).isLessThan(body.indexOf("\"holidays\""));
    }

    @Test
    void theAnswerCarriesTheLicenceNoticeCcBySaRequires() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL))
                .andExpect(jsonPath("$.data.attribution.source").value("Wikipedia"))
                .andExpect(jsonPath("$.data.attribution.license").value("CC BY-SA 4.0"))
                .andExpect(jsonPath("$.data.attribution.licenseUrl")
                        .value("https://creativecommons.org/licenses/by-sa/4.0/"))
                .andExpect(jsonPath("$.data.attribution.notice").isNotEmpty());
    }

    @Test
    void theRequestIdIsOnTheResponseAndInTheEnvelope() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        MvcResult mvcResult = mvc.perform(get(URL)).andReturn();
        String header = mvcResult.getResponse().getHeader("X-Request-Id");

        assertThat(header).matches("REQ_[0-9A-F]{8}");
        assertThat(mvcResult.getResponse().getContentAsString()).contains("\"sessionId\":\"" + header + "\"");
    }

    // ----- caching ----------------------------------------------------------------------

    @Test
    void aGoodAnswerMayBeCachedByBrowsersAndProxiesForFiveMinutes() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL))
                .andExpect(header().string("Cache-Control", "max-age=300, public"));
    }

    @Test
    void aDegradedAnswerIsCachedForHalfAMinuteOnly() throws Exception {
        given(service.find(any())).willReturn(result(List.of("FALLBACK_UNAVAILABLE"), false));
        mvc.perform(get(URL)).andExpect(header().string("Cache-Control", "max-age=30, public"));

        given(service.find(any())).willReturn(result(List.of(), true));
        mvc.perform(get(URL)).andExpect(header().string("Cache-Control", "max-age=30, public"));
    }

    // ----- how parameters reach the service ---------------------------------------------

    @Test
    void withNoParametersTheDayComesFromThePathAndTheLanguageDefaultsToItalian() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get("/api/history/on-this-day/2/29"));

        OnThisDayQuery query = queryPassedToTheService();
        assertThat(query.day()).isEqualTo(MonthDay.of(2, 29));
        assertThat(query.language()).isEqualTo(Language.IT);
        assertThat(query.sections()).hasSize(Section.values().length);
        assertThat(query.years()).isEqualTo(YearRange.unbounded());
    }

    @Test
    void typesMayBeCommaSeparated() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL).param("lang", "en").param("types", "events,births"));

        OnThisDayQuery query = queryPassedToTheService();
        assertThat(query.language()).isEqualTo(Language.EN);
        assertThat(query.sections()).containsExactlyInAnyOrder(Section.EVENTS, Section.BIRTHS);
    }

    @Test
    void typesMayAlsoBeRepeated() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL).param("types", "deaths").param("types", "holidays"));

        assertThat(queryPassedToTheService().sections()).containsExactlyInAnyOrder(Section.DEATHS, Section.HOLIDAYS);
    }

    @Test
    void anExactYearAndARangeBothArePassedOn() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL).param("year", "-44"));
        assertThat(queryPassedToTheService().years()).isEqualTo(new YearRange(-44, -44));
    }

    @Test
    void aRangeIsPassedOn() throws Exception {
        given(service.find(any())).willReturn(result(List.of(), false));

        mvc.perform(get(URL).param("fromYear", "1900").param("toYear", "1999"));

        assertThat(queryPassedToTheService().years()).isEqualTo(new YearRange(1900, 1999));
    }

    // ----- what the caller got wrong ----------------------------------------------------

    private void assertRejected(String url, String errorCode) throws Exception {
        mvc.perform(get(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(errorCode))
                .andExpect(jsonPath("$.userMessage").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    void aDayThatDoesNotExistIsA400_andNothingIsAskedOfWikipedia() throws Exception {
        assertRejected("/api/history/on-this-day/2/30", "INVALID_DATE");
    }

    @Test
    void aMonthOutOfRangeIsA400() throws Exception {
        assertRejected("/api/history/on-this-day/13/40", "INVALID_DATE");
    }

    @Test
    void anUnsupportedLanguageIsA400() throws Exception {
        assertRejected(URL + "?lang=fr", "UNSUPPORTED_LANGUAGE");
    }

    @Test
    void anUnknownTypeIsA400() throws Exception {
        assertRejected(URL + "?types=wars", "INVALID_TYPE");
    }

    @Test
    void contradictoryYearParametersAreA400() throws Exception {
        assertRejected(URL + "?year=1978&fromYear=1900", "INVALID_YEAR");
    }

    @Test
    void aMonthThatIsNotANumberIsA400InTheEnvelope_notSpringsOwnErrorPage() throws Exception {
        mvc.perform(get("/api/history/on-this-day/october/16"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.userMessage").value("La richiesta non e' valida."));
        verifyNoInteractions(service);
    }

    @Test
    void aYearThatIsNotANumberIsA400Too() throws Exception {
        mvc.perform(get(URL).param("year", "soon"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void anUnknownPathIsA404InTheEnvelope() throws Exception {
        mvc.perform(get("/api/history/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aWrongMethodIsA405InTheEnvelope_withAnAllowHeader() throws Exception {
        mvc.perform(post(URL))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    // ----- Wikipedia's failures ---------------------------------------------------------

    @Test
    void whenWikipediaIsRateLimitingTheAnswerIsA503WithTheWaitRoundedUp() throws Exception {
        given(service.find(any())).willThrow(new UpstreamRateLimitedException(Duration.ofMillis(6200)));

        mvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.error").value("UPSTREAM_RATE_LIMITED"));
    }

    @Test
    void aWaitUnderASecondIsStillAtLeastOneSecond() throws Exception {
        given(service.find(any())).willThrow(new UpstreamRateLimitedException(Duration.ofMillis(20)));

        mvc.perform(get(URL)).andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void whenWikipediaIsUnreachableAndThereIsNothingToFallBackOnTheAnswerIsA503() throws Exception {
        given(service.find(any())).willThrow(new UpstreamUnavailableException("it down", new RuntimeException("secret")));

        mvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.userMessage").isNotEmpty());
    }

    @Test
    void whenWikipediaSendsSomethingUnusableTheAnswerIsA502() throws Exception {
        given(service.find(any())).willThrow(new UpstreamBadResponseException("Wikipedia answered 404 to a validated request"));

        mvc.perform(get(URL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("UPSTREAM_BAD_RESPONSE"));
    }

    @Test
    void anUnexpectedErrorIsA500ThatLeaksNothing() throws Exception {
        given(service.find(any())).willThrow(new IllegalStateException("password=hunter2 at /internal/path"));

        String body = mvc.perform(get(URL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("hunter2").doesNotContain("/internal/path");
    }

    @Test
    void everyErrorCarriesTheRequestId() throws Exception {
        MvcResult mvcResult = mvc.perform(get(URL + "?lang=zz")).andReturn();

        String id = mvcResult.getResponse().getHeader("X-Request-Id");
        assertThat(id).isNotBlank();
        assertThat(mvcResult.getResponse().getContentAsString()).contains("\"sessionId\":\"" + id + "\"");
    }
}
