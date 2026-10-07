package centuryroad.history.service;

import centuryroad.history.model.DatePrecision;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Insight;
import centuryroad.history.model.Topic;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.MonthDay;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the catalog reads, orders and - above all - refuses content. Each refusal is a mistake a
 * person can make while editing JSON by hand, so each is a test that names it.
 */
class EditorialCatalogTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SLUGS = List.of("a", "b", "c", "d", "e", "f");

    @TempDir
    Path root;

    @BeforeEach
    void writeValidContent() throws IOException {
        for (int i = 0; i < SLUGS.size(); i++) {
            writeInsight(SLUGS.get(i), 1900 + i * 10, 3, 5, node -> { });
        }
        writePath("route", SLUGS, node -> { });
        writeStartHere("""
                {"picks":[{"type":"PATH","slug":"route","teaser":"Qui."},{"type":"INSIGHT","slug":"c","teaser":"O qui."}]}""");
    }

    private EditorialCatalog catalog() {
        return new EditorialCatalog(new PathMatchingResourcePatternResolver(), "file:" + root);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private void writeInsight(String slug, int year, int month, int day, Consumer<ObjectNode> edit) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("slug", slug).put("title", "Titolo " + slug).put("summary", "Sintesi.");
        node.putObject("date").put("year", year).put("month", month).put("day", day);
        node.putObject("place").put("name", "Luogo").put("lat", 10.0).put("lon", 20.0)
                .put("countryCode", "IT").put("approximate", false);
        node.put("before", "Prima.").put("event", "Evento.").put("after", "Dopo.");
        ArrayNode links = node.putArray("links");
        SLUGS.stream().filter(s -> !s.equals(slug)).limit(2)
                .forEach(other -> links.addObject().put("slug", other).put("reason", "Perché."));
        node.putArray("sources").addObject().put("title", "Fonte").put("url", "https://it.wikipedia.org/wiki/X")
                .put("publisher", "Wikipedia");
        node.putObject("provenance").put("author", "Century Road");
        edit.accept(node);
        write("insights/" + slug + ".json", node);
    }

    private static void precision(ObjectNode insight, String precision) {
        ((ObjectNode) insight.get("date")).put("precision", precision);
    }

    private void writePath(String slug, List<String> stops, Consumer<ObjectNode> edit) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("slug", slug).put("title", "Percorso").put("tagline", "Una frase.").put("intro", "Una introduzione.")
                .put("topic", "ETA_MODERNA");
        ArrayNode array = node.putArray("stops");
        stops.forEach(s -> array.addObject().put("insight", s).put("narrative", "Perché " + s + "."));
        edit.accept(node);
        write("paths/" + slug + ".json", node);
    }

    private void writeStartHere(String json) throws IOException {
        Files.writeString(root.resolve("start-here.json"), json);
    }

    private void write(String name, ObjectNode node) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(node));
    }

    private void assertRefused(String expectedProblem) {
        assertThatThrownBy(this::catalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The editorial content is not valid")
                .hasMessageContaining(expectedProblem);
    }

    // ---- reading -------------------------------------------------------------------------------

    @Test
    void validContentLoads_withInsightsOldestFirst_andPathsAndPicks() {
        EditorialCatalog catalog = catalog();

        assertThat(catalog.insights()).extracting(Insight::slug).containsExactly("a", "b", "c", "d", "e", "f");
        assertThat(catalog.paths()).extracting(GuidedPath::slug).containsExactly("route");
        assertThat(catalog.startHere()).hasSize(2);
    }

    @Test
    void insightsAreOrderedByDateNotByFileName() throws IOException {
        writeInsight("a", 2020, 1, 1, node -> { });
        writeInsight("f", 1800, 1, 1, node -> { });

        assertThat(catalog().insights()).extracting(Insight::slug).first().isEqualTo("f");
        assertThat(catalog().insights()).extracting(Insight::slug).last().isEqualTo("a");
    }

    @Test
    void aDayFindsItsInsightsWhateverTheYear() throws IOException {
        writeInsight("a", 1901, 7, 20, node -> { });
        writeInsight("b", 1969, 7, 20, node -> { });
        writeInsight("c", 1969, 7, 21, node -> { });

        assertThat(catalog().insightsOn(MonthDay.of(7, 20))).extracting(Insight::slug).containsExactly("a", "b");
        assertThat(catalog().insightsOn(MonthDay.of(12, 25))).isEmpty();
    }

    @Test
    void aDateWithNoPrecisionIsKnownToTheDay_andOneThatSaysOtherwiseKeepsIt() throws IOException {
        writeInsight("b", -133, 1, 1, node -> precision(node, "YEAR"));
        writeInsight("c", 1900, 7, 1, node -> precision(node, "MONTH"));

        EditorialCatalog catalog = catalog();

        assertThat(catalog.insight("a").orElseThrow().date().precision()).isEqualTo(DatePrecision.DAY);
        assertThat(catalog.insight("b").orElseThrow().date().precision()).isEqualTo(DatePrecision.YEAR);
        assertThat(catalog.insight("c").orElseThrow().date().precision()).isEqualTo(DatePrecision.MONTH);
    }

    @Test
    void aDayDoesNotFindTheInsightsThatKnowOnlyTheYearOrTheMonth() throws IOException {
        writeInsight("a", -133, 1, 1, node -> precision(node, "YEAR"));
        writeInsight("b", 1900, 7, 1, node -> precision(node, "MONTH"));
        writeInsight("c", 1969, 1, 1, node -> { });

        assertThat(catalog().insightsOn(MonthDay.of(1, 1))).extracting(Insight::slug).containsExactly("c");
        assertThat(catalog().insightsOn(MonthDay.of(7, 1))).isEmpty();
    }

    @Test
    void anInsightKnowsWhichPathsHaveAStopOnIt() throws IOException {
        writePath("short", SLUGS.subList(0, 6), node -> { });

        assertThat(catalog().pathsContaining("a")).extracting(GuidedPath::slug).containsExactly("route", "short");
        assertThat(catalog().pathsContaining("nowhere")).isEmpty();
    }

    @Test
    void readingTimeIsCountedFromTheWords_roundedUp_neverBelowOne() throws IOException {
        // 6 stops, each: narrative 3 words ("Perché x.") + summary 1 + before 1 + event 1 + after 1
        // = 7 words, plus tagline 2 and intro 2 = 46 words: one minute.
        assertThat(catalog().readingMinutes(catalog().path("route").orElseThrow())).isEqualTo(1);

        String longText = "parola ".repeat(500).trim();
        writeInsight("a", 1900, 3, 5, node -> node.put("event", longText));

        // 46 + 500 - 1 words = 545 at 200 a minute: three minutes, not 2.7.
        assertThat(catalog().readingMinutes(catalog().path("route").orElseThrow())).isEqualTo(3);
    }

    // ---- refusing ------------------------------------------------------------------------------

    @Test
    void aKeyItDoesNotKnowIsAMistake_notSomethingToIgnore() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> node.put("befor", "Un refuso."));

        assertRefused("a.json");
        assertRefused("befor");
    }

    @Test
    void aSlugThatIsNotTheFileNameIsRefused() throws IOException {
        Files.move(root.resolve("insights/a.json"), root.resolve("insights/renamed.json"));

        assertRefused("the file must be called a.json");
    }

    @Test
    void aMissingTextIsRefused_withTheInsightAndTheFieldNamed() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> node.remove("after"));

        assertRefused("insight a: after is missing");
    }

    @Test
    void anImpossibleDateIsRefused() throws IOException {
        writeInsight("a", 1900, 2, 30, node -> { });

        assertRefused("insight a: date is not a real date");
    }

    @Test
    void aMissingYearIsRefused_becauseItWouldReadAsYearZero() throws IOException {
        writeInsight("a", 0, 3, 5, node -> { });

        assertRefused("insight a: date is not a real date");
    }

    @Test
    void aDateKnownOnlyByItsYearIsWrittenAsTheFirstOfJanuary_andOneKnownByItsMonthAsTheFirst() throws IOException {
        writeInsight("a", -133, 7, 14, node -> precision(node, "YEAR"));
        writeInsight("b", 1900, 7, 14, node -> precision(node, "MONTH"));

        assertRefused("insight a: a date known only by its year is written as 1 January");
        assertRefused("insight b: a date known only by its month is written as the 1st of that month");
    }

    @Test
    void aPrecisionItDoesNotKnowIsAMistake() throws IOException {
        writeInsight("a", 1900, 1, 1, node -> precision(node, "SEASON"));

        assertRefused("a.json");
        assertRefused("SEASON");
    }

    @Test
    void aNumberWhereAnEnumBelongsIsAMistake_notThePositionOfAValue() throws IOException {
        writeInsight("a", 1900, 1, 1, node -> ((ObjectNode) node.get("date")).put("precision", 0));
        writePath("route", SLUGS, node -> node.put("topic", 1));

        assertRefused("a.json");
        assertRefused("route.json");
    }

    @Test
    void aYearOnlyDateIsRefusedWhenEitherPlaceholderIsOff_theMonthOrTheDay() throws IOException {
        writeInsight("a", -133, 6, 1, node -> precision(node, "YEAR"));
        writeInsight("b", -133, 1, 2, node -> precision(node, "YEAR"));

        assertRefused("insight a: a date known only by its year is written as 1 January");
        assertRefused("insight b: a date known only by its year is written as 1 January");
    }

    @Test
    void aPlaceWithoutCoordinatesIsRefused_becauseNullIslandIsNotAPlace() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> ((ObjectNode) node.get("place")).put("lat", 0.0).put("lon", 0.0));

        assertRefused("insight a: place has no usable coordinates");
    }

    @Test
    void anApproximatePinMustSayWhy_andANoteMeansApproximate() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> ((ObjectNode) node.get("place")).put("approximate", true));
        writeInsight("b", 1910, 3, 5, node -> ((ObjectNode) node.get("place")).put("note", "Un punto di partenza."));

        assertRefused("insight a: place.approximate and place.note go together");
        assertRefused("insight b: place.approximate and place.note go together");
    }

    @Test
    void aBadCountryCodeIsRefused() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> ((ObjectNode) node.get("place")).put("countryCode", "it"));

        assertRefused("insight a: place.countryCode must be two uppercase letters");
    }

    @Test
    void anInsightNeedsTwoOrThreeLinks_toInsightsThatExist() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> ((ArrayNode) node.get("links")).remove(1));
        writeInsight("b", 1910, 3, 5, node -> ((ArrayNode) node.get("links")).addObject()
                .put("slug", "ghost").put("reason", "Boh."));

        assertRefused("insight a: needs 2 to 3 links, has 1");
        assertRefused("insight b: links to ghost, which does not exist");
    }

    @Test
    void anInsightCannotLinkToItself_orTwiceToTheSame() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> ((ArrayNode) node.get("links")).addObject()
                .put("slug", "a").put("reason", "Sé stesso."));
        writeInsight("b", 1910, 3, 5, node -> {
            ArrayNode links = (ArrayNode) node.get("links");
            links.removeAll();
            links.addObject().put("slug", "c").put("reason", "Uno.");
            links.addObject().put("slug", "c").put("reason", "Due.");
        });

        assertRefused("insight a: links to itself");
        assertRefused("insight b: links to c twice");
    }

    @Test
    void aReviewDateIsTheDaySomebodyReallyReviewed_soNotInTheFuture() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> ((ObjectNode) node.get("provenance")).put("reviewedAt", "2999-01-01"));
        assertRefused("insight a: provenance.reviewedAt is in the future");

        writeInsight("a", 1900, 3, 5, node -> ((ObjectNode) node.get("provenance")).put("reviewedAt", "2026-09-01"));
        assertThat(catalog().insight("a").orElseThrow().provenance().reviewedAt()).hasToString("2026-09-01");
    }

    @Test
    void aSourceNeedsAnHttpsLink() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> ((ObjectNode) node.get("sources").get(0)).put("url", "http://example.org"));

        assertRefused("insight a: every source needs a title, a publisher and an https url");
    }

    @Test
    void aPathWithoutATopicIsRefused_andSoIsOneThatIsNotInTheList() throws IOException {
        writePath("route", SLUGS, node -> node.remove("topic"));
        assertRefused("path route: topic is missing");

        writePath("route", SLUGS, node -> node.put("topic", "STORIA_A_CASO"));
        assertRefused("route.json");
        assertRefused("STORIA_A_CASO");
    }

    @Test
    void anInsightWithNoSlugIsRefused_notSkippedInSilence() throws IOException {
        writeInsight("a", 1900, 3, 5, node -> node.remove("slug"));

        assertRefused("a.json: the insight has no slug");
    }

    @Test
    void aPathKeepsItsTopic_whetherOrNotItHasACover() throws IOException {
        assertThat(catalog().path("route").orElseThrow().topic()).isEqualTo(Topic.ETA_MODERNA);

        writePath("route", SLUGS, node -> node.putObject("cover")
                .put("imageUrl", "https://upload.wikimedia.org/wikipedia/commons/9/97/The_Earth_seen_from_Apollo_17.jpg")
                .put("alt", "La Terra."));
        assertThat(catalog().path("route").orElseThrow().topic()).isEqualTo(Topic.ETA_MODERNA);
    }

    @Test
    void aPathSpansTheYearsOfItsStops_negativeBeforeTheCommonEra() throws IOException {
        // The stops are a..f in 1900, 1910, ... 1950: the order of the list does not matter, the dates do.
        assertThat(catalog().startYear(catalog().path("route").orElseThrow())).isEqualTo(1900);
        assertThat(catalog().endYear(catalog().path("route").orElseThrow())).isEqualTo(1950);

        writeInsight("a", -44, 3, 15, node -> { });
        assertThat(catalog().startYear(catalog().path("route").orElseThrow())).isEqualTo(-44);
    }

    @Test
    void pathsAreGroupedByTopicAsDeclared_thenOldestFirst_thenBySlug() throws IOException {
        // Declared order: MONDO_ANTICO < ... < ETA_MODERNA (the default here) < ... < SCIENZA.
        writePath("late-science", SLUGS, node -> node.put("topic", "SCIENZA"));
        writePath("ancient", SLUGS, node -> node.put("topic", "MONDO_ANTICO"));
        // Same topic as "route" (first stop 1900): one with a stop in 1800 comes first, one that
        // starts the same year comes after it by slug.
        writeInsight("g", 1800, 1, 1, node -> { });
        writePath("earlier", List.of("b", "c", "d", "e", "f", "g"), node -> { });
        writePath("zzz-same-start", SLUGS, node -> { });

        assertThat(catalog().paths()).extracting(GuidedPath::slug)
                .containsExactly("ancient", "earlier", "route", "zzz-same-start", "late-science");
    }

    @Test
    void aPathOfThreeStopsIsRefused() throws IOException {
        writePath("route", SLUGS.subList(0, 3), node -> { });

        assertRefused("path route: needs 6 to 10 stops, has 3");
    }

    @Test
    void aStopOnAnInsightThatDoesNotExistIsRefused() throws IOException {
        writePath("route", List.of("a", "b", "c", "d", "e", "ghost"), node -> { });

        assertRefused("path route: a stop opens ghost, which does not exist");
    }

    @Test
    void aStopCannotAppearTwiceInOnePath() throws IOException {
        writePath("route", List.of("a", "b", "c", "d", "e", "e"), node -> { });

        assertRefused("path route: opens e twice");
    }

    @Test
    void aCoverMustComeFromCommons_andItsFilePageIsDerivedNotWritten() throws IOException {
        writePath("route", SLUGS, node -> node.putObject("cover")
                .put("imageUrl", "https://example.org/blue-marble.jpg").put("alt", "La Terra."));
        assertRefused("path route: the cover must be an image hosted on Wikimedia Commons");

        writePath("route", SLUGS, node -> node.putObject("cover")
                .put("imageUrl", "https://upload.wikimedia.org/wikipedia/commons/9/97/The_Earth_seen_from_Apollo_17.jpg")
                .put("alt", "La Terra."));
        assertThat(catalog().path("route").orElseThrow().cover().filePageUrl())
                .isEqualTo("https://commons.wikimedia.org/wiki/File:The_Earth_seen_from_Apollo_17.jpg");
    }

    @Test
    void aCoverNeedsADescription() throws IOException {
        writePath("route", SLUGS, node -> node.putObject("cover")
                .put("imageUrl", "https://upload.wikimedia.org/wikipedia/commons/9/97/The_Earth_seen_from_Apollo_17.jpg"));

        assertRefused("path route: the cover needs a description (alt)");
    }

    @Test
    void aStartHerePickThatPointsAtNothingIsRefused() throws IOException {
        writeStartHere("""
                {"picks":[{"type":"PATH","slug":"ghost","teaser":"Qui."}]}""");

        assertRefused("start-here.json: PATH ghost does not exist");
    }

    @Test
    void startHereNeedsPicks_andNotTooMany() throws IOException {
        writeStartHere("{\"picks\":[]}");
        assertRefused("start-here.json: needs between 1 and 6 picks");

        writeStartHere("{\"picks\":" + JSON.writeValueAsString(SLUGS.stream()
                .map(s -> java.util.Map.of("type", "INSIGHT", "slug", s, "teaser", "Qui."))
                .toList()) + "}");
        assertThat(catalog().startHere()).hasSize(6);

        Files.delete(root.resolve("start-here.json"));
        assertRefused("start-here.json: missing");
    }

    @Test
    void everyProblemIsReportedAtOnce_notJustTheFirst() throws IOException {
        writeInsight("a", 1900, 2, 30, node -> node.remove("after"));
        writePath("route", SLUGS.subList(0, 3), node -> { });

        assertThatThrownBy(this::catalog).hasMessageContaining("insight a: after is missing")
                .hasMessageContaining("insight a: date is not a real date")
                .hasMessageContaining("path route: needs 6 to 10 stops");
    }
}
