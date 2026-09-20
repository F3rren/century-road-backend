package centuryroad.history.wikipedia;

import centuryroad.history.Fixtures;
import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Entry;
import centuryroad.history.model.Language;
import centuryroad.history.model.PageRef;
import centuryroad.history.model.Section;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikimediaFeedParserUnitTest {

    private static final MonthDay DAY = MonthDay.of(10, 16);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DayFeed feed = WikimediaFeedParser.parse(Fixtures.json("it-10-16.json"), Language.IT, DAY);

    private static DayFeed parse(String json) throws Exception {
        return WikimediaFeedParser.parse(MAPPER.readTree(json), Language.IT, DAY);
    }

    /** The only page of a one-entry feed, built from extra fields on top of a minimal valid page. */
    private static PageRef onlyPage(String extraFields) throws Exception {
        String page = "{\"title\":\"Pagina\",\"content_urls\":{\"desktop\":{\"page\":\"https://it.wikipedia.org/wiki/Pagina\"}}"
                + (extraFields.isEmpty() ? "" : "," + extraFields) + "}";
        DayFeed parsed = parse("{\"events\":[{\"text\":\"ok\",\"year\":1900,\"pages\":[" + page + "]}]}");
        return parsed.entries(Section.EVENTS).get(0).pages().get(0);
    }

    @Test
    void everySectionIsPresent_evenWhenWikipediaSentNothingForIt() {
        assertThat(feed.sections()).containsOnlyKeys(Section.values());
    }

    @Test
    void anEmptySectionIsEmptyWhetherWikipediaSentAnEmptyArrayOrAnEmptyObject() {
        assertThat(feed.entries(Section.BIRTHS)).isEmpty();   // []
        assertThat(feed.entries(Section.DEATHS)).isEmpty();   // {}
    }

    @Test
    void aWholeResponseOfEmptyObjectsIsNotAnError() throws Exception {
        // What the feed answers for 13/40, or for a language it has no data for.
        DayFeed empty = parse("{\"selected\":{},\"births\":{},\"deaths\":{},\"events\":{},\"holidays\":{}}");

        assertThat(empty.sections().values()).allMatch(java.util.List::isEmpty);
    }

    @Test
    void textYearAndPagesAreCarriedOver() {
        Entry selected = feed.entries(Section.SELECTED).get(0);

        assertThat(selected.text()).isEqualTo("Un cardinale polacco viene eletto papa.");
        assertThat(selected.year()).isEqualTo(1978);
        assertThat(selected.pages()).hasSize(2);
    }

    @Test
    void aPageKeepsItsTitleDescriptionExtractAndAttributionLink() {
        PageRef page = feed.entries(Section.SELECTED).get(0).pages().get(0);

        assertThat(page.title()).isEqualTo("Papa Esempio");
        assertThat(page.description()).isEqualTo("papa della Chiesa cattolica");
        assertThat(page.extract()).startsWith("Il papa di esempio");
        assertThat(page.url()).isEqualTo("https://it.wikipedia.org/wiki/Papa_Esempio");
    }

    @Test
    void aCommonsThumbnailIsKeptWithTheLinkToItsFilePage() {
        PageRef page = feed.entries(Section.SELECTED).get(0).pages().get(0);

        assertThat(page.thumbnail()).isNotNull();
        assertThat(page.thumbnail().url()).contains("/wikipedia/commons/thumb/");
        assertThat(page.thumbnail().width()).isEqualTo(330);
        assertThat(page.thumbnail().height()).isEqualTo(412);
        assertThat(page.thumbnail().filePageUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:Papa_Esempio.jpg");
    }

    @Test
    void aCommonsThumbnailServedFromTheThumbHostIsKeptWithTheLinkToItsFilePage() throws Exception {
        PageRef page = onlyPage("\"thumbnail\":{\"source\":\"https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b4/Prodi.jpg"
                + "/330px-Prodi.jpg?utm_source=it.wikipedia.org&utm_campaign=api\",\"width\":330,\"height\":440}");

        assertThat(page.thumbnail()).isNotNull();
        assertThat(page.thumbnail().filePageUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:Prodi.jpg");
    }

    @Test
    void aThumbnailFromALocalWikiIsDropped_butItsPageIsKept() {
        PageRef page = feed.entries(Section.SELECTED).get(0).pages().get(1);

        assertThat(page.title()).isEqualTo("Conclave di esempio");
        assertThat(page.thumbnail()).isNull();
    }

    @Test
    void anEntryWithNoPagesIsStillAnEntry() {
        Entry entry = feed.entries(Section.EVENTS).get(0);

        assertThat(entry.text()).isEqualTo("Un evento antico senza pagine collegate.");
        assertThat(entry.pages()).isEmpty();
    }

    @Test
    void aYearBeforeTheCommonEraStaysNegative() {
        assertThat(feed.entries(Section.EVENTS).get(1).year()).isEqualTo(-44);
    }

    @Test
    void theTitleFallsBackToTheNormalizedOneWhenTheTopLevelFieldIsMissing() {
        assertThat(feed.entries(Section.EVENTS).get(1).pages().get(0).title()).isEqualTo("Roma antica");
    }

    @Test
    void aPageWithALinkThatIsNotOnWikipediaIsDiscarded_theOtherOnesSurvive() {
        Entry entry = feed.entries(Section.EVENTS).get(2);

        assertThat(entry.pages()).extracting(PageRef::title).containsExactly("Pagina buona");
        assertThat(entry.pages().get(0).description()).isNull();
        assertThat(entry.pages().get(0).extract()).isNull();
    }

    @Test
    void aHolidayHasNoYear() {
        Entry holiday = feed.entries(Section.HOLIDAYS).get(0);

        assertThat(holiday.year()).isNull();
        assertThat(holiday.pages()).hasSize(1);
    }

    @Test
    void nothingHtmlSurvivesIntoTheModel() throws Exception {
        String everything = MAPPER.writeValueAsString(feed.sections());

        assertThat(everything).doesNotContain("<span").doesNotContain("<p>").doesNotContain("extract_html");
    }

    @Test
    void anItemWithoutTextIsSkipped_andANonNumericYearBecomesNoYear() throws Exception {
        DayFeed parsed = parse("{\"events\":[{\"year\":1900,\"pages\":[]},{\"text\":\"ok\",\"year\":\"1900\"}]}");

        assertThat(parsed.entries(Section.EVENTS)).hasSize(1);
        assertThat(parsed.entries(Section.EVENTS).get(0).year()).isNull();
    }

    @Test
    void pagesThatAreNotAListAreIgnored() throws Exception {
        DayFeed parsed = parse("{\"events\":[{\"text\":\"ok\",\"year\":1900,\"pages\":{}}]}");

        assertThat(parsed.entries(Section.EVENTS).get(0).pages()).isEmpty();
    }

    @Test
    void aBodyThatIsNotAnObjectIsAProtocolProblemNotAnEmptyDay() throws Exception {
        for (String body : new String[]{"[]", "\"text\"", "42", "null"}) {
            JsonNode node = MAPPER.readTree(body);
            assertThatThrownBy(() -> WikimediaFeedParser.parse(node, Language.IT, DAY))
                    .isInstanceOf(UpstreamBadResponseException.class);
        }
    }
}
