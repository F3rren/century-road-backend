package centuryroad.history.service;

import centuryroad.history.TestClock;
import centuryroad.history.model.DatePrecision;
import centuryroad.history.model.EventDate;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Insight;
import centuryroad.history.model.InsightLink;
import centuryroad.history.model.PathStop;
import centuryroad.history.model.Place;
import centuryroad.history.model.Provenance;
import centuryroad.history.model.Source;
import centuryroad.history.model.StartHere;
import centuryroad.history.model.StartHerePick;
import centuryroad.history.model.Topic;
import centuryroad.history.service.EditorialLoader.Loaded;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The content rules on their own: a few lines of Java each, no directory of JSON to write and
 * no Spring. EditorialCatalogTest still proves the same rules end to end through real files;
 * this is what the split buys, and what a new rule can be developed against.
 */
class EditorialValidatorTest {

    // 2026-09-18, and it only moves when a test says so: the one rule that depends on today
    // no longer depends on the day the build happens to run.
    private final TestClock clock = TestClock.atNoon();
    private final EditorialValidator validator = new EditorialValidator(clock);

    // ---- fixtures ------------------------------------------------------------------------------

    private static Insight insight(String slug, Place place, Provenance provenance) {
        return new Insight(slug, "Titolo", "Sintesi.", new EventDate(1957, 10, 4, DatePrecision.DAY), place,
                "Prima.", "Evento.", "Dopo.",
                List.of(new InsightLink("b", "Perché."), new InsightLink("c", "Perché.")),
                List.of(new Source("Fonte", "https://it.wikipedia.org/wiki/X", "Wikipedia", null)),
                List.of(), provenance);
    }

    private static final Place PLACE = new Place("Luogo", 45.9, 63.3, "KZ", false, null);
    private static final Provenance AUTHOR_ONLY = new Provenance("Century Road", null);

    private static Map<String, Insight> insights() {
        return Map.of("a", insight("a", PLACE, AUTHOR_ONLY), "b", insight("b", PLACE, AUTHOR_ONLY),
                "c", insight("c", PLACE, AUTHOR_ONLY), "d", insight("d", PLACE, AUTHOR_ONLY),
                "e", insight("e", PLACE, AUTHOR_ONLY), "f", insight("f", PLACE, AUTHOR_ONLY));
    }

    private static GuidedPath path(String slug, List<String> stopSlugs) {
        return new GuidedPath(slug, "Percorso", "Una frase.", "Una introduzione.", Topic.MEDIOEVO, null,
                stopSlugs.stream().map(s -> new PathStop(s, "Perché " + s + ".")).toList());
    }

    /** What a check reported, as the message the service would stop with; empty when it found nothing. */
    private static String problemsOf(Consumer<EditorialProblems> checks) {
        EditorialProblems problems = new EditorialProblems();
        checks.accept(problems);
        try {
            problems.failIfAny();
            return "";
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    // ---- insights ------------------------------------------------------------------------------

    @Test
    void aValidInsightHasNoProblem() {
        Map<String, Insight> all = insights();

        assertThat(problemsOf(p -> validator.checkInsight(all.get("a"), all, p))).isEmpty();
    }

    @Test
    void aReviewDateIsRefusedOnlyWhenItIsAfterToday_whateverDayTheBuildRuns() {
        Map<String, Insight> all = insights();
        Insight reviewedTomorrow = insight("a", PLACE, new Provenance("Century Road", LocalDate.of(2026, 9, 19)));
        Insight reviewedToday = insight("a", PLACE, new Provenance("Century Road", LocalDate.of(2026, 9, 18)));

        assertThat(problemsOf(p -> validator.checkInsight(reviewedTomorrow, all, p)))
                .contains("insight a: provenance.reviewedAt is in the future");
        assertThat(problemsOf(p -> validator.checkInsight(reviewedToday, all, p))).isEmpty();

        // A day later, "tomorrow" has become today.
        clock.advance(Duration.ofDays(1));
        assertThat(problemsOf(p -> validator.checkInsight(reviewedTomorrow, all, p))).isEmpty();
    }

    @Test
    void anApproximatePinAndItsNoteGoTogether() {
        Map<String, Insight> all = insights();
        Place approximateWithoutNote = new Place("Luogo", 45.9, 63.3, "KZ", true, null);
        Place noteWithoutApproximate = new Place("Luogo", 45.9, 63.3, "KZ", false, "Perché qui.");
        Place both = new Place("Luogo", 45.9, 63.3, "KZ", true, "Perché qui.");

        String reason = "place.approximate and place.note go together";
        assertThat(problemsOf(p -> validator.checkInsight(insight("a", approximateWithoutNote, AUTHOR_ONLY), all, p)))
                .contains(reason);
        assertThat(problemsOf(p -> validator.checkInsight(insight("a", noteWithoutApproximate, AUTHOR_ONLY), all, p)))
                .contains(reason);
        assertThat(problemsOf(p -> validator.checkInsight(insight("a", both, AUTHOR_ONLY), all, p))).isEmpty();
    }

    @Test
    void aFileMustBeNamedAfterItsSlug_andASlugCanBeUsedOnce() {
        Loaded<Insight> misnamed = new Loaded<>("wrong.json", "medioevo", insight("a", PLACE, AUTHOR_ONLY));
        Loaded<Insight> first = new Loaded<>("b.json", "medioevo", insight("b", PLACE, AUTHOR_ONLY));
        Loaded<Insight> second = new Loaded<>("b.json", "rinascimento", insight("b", PLACE, AUTHOR_ONLY));

        String problems = problemsOf(p -> validator.indexInsights(List.of(misnamed, first, second), p));

        assertThat(problems)
                .contains("wrong.json: the slug is a, so the file must be called a.json")
                .contains("rinascimento/b.json: the slug b is already used by another insight");
    }

    @Test
    void everyProblemIsReportedTogether_notOnePerRun() {
        Insight broken = new Insight("a", " ", null, new EventDate(0, 1, 1, DatePrecision.DAY), null, null, null, null,
                List.of(), List.of(), List.of(), null);

        String problems = problemsOf(p -> validator.checkInsight(broken, insights(), p));

        assertThat(problems)
                .startsWith("The editorial content is not valid:\n - ")
                .contains("title is missing", "summary is missing", "before is missing", "date is not a real date",
                        "place is missing", "needs 2 to 3 links", "needs at least one source",
                        "provenance.author is missing");
    }

    // ---- paths ---------------------------------------------------------------------------------

    @Test
    void aPathNeedsSixToTenStops_eachOpeningADifferentRealInsight() {
        Map<String, Insight> all = insights();

        assertThat(problemsOf(p -> validator.checkPath("route.json", path("route", List.of("a", "b", "c")), all, p)))
                .contains("path route: needs 6 to 10 stops, has 3");
        assertThat(problemsOf(p -> validator.checkPath("route.json",
                path("route", List.of("a", "b", "c", "d", "e", "zzz")), all, p)))
                .contains("a stop opens zzz, which does not exist");
        assertThat(problemsOf(p -> validator.checkPath("route.json",
                path("route", List.of("a", "b", "c", "d", "e", "a")), all, p)))
                .contains("path route: opens a twice");
        assertThat(problemsOf(p -> validator.checkPath("route.json",
                path("route", List.of("a", "b", "c", "d", "e", "f")), all, p))).isEmpty();
    }

    @Test
    void aPathFileBelongsInTheFolderOfItsTopic() {
        Loaded<GuidedPath> wrongFolder = new Loaded<>("route.json", "rinascimento",
                path("route", List.of("a", "b", "c", "d", "e", "f")));

        String problems = problemsOf(p -> validator.indexPaths(List.of(wrongFolder), insights(), p));

        assertThat(problems).contains("rinascimento/route.json: the path is about MEDIOEVO, so the file goes in the "
                + "folder medioevo");
    }

    // ---- "Inizia da qui" ----------------------------------------------------------------------

    @Test
    void startHereNeedsSomePicks_notTooMany_eachOnceAndEachExisting() {
        Map<String, Insight> all = insights();
        Map<String, GuidedPath> paths = Map.of("route", path("route", List.of("a", "b", "c", "d", "e", "f")));

        List<StartHerePick> tooMany = new ArrayList<>(IntStream.range(0, 7)
                .mapToObj(i -> new StartHerePick(StartHerePick.Type.INSIGHT, "a", "Qui " + i)).toList());

        assertThat(problemsOf(p -> validator.checkStartHere(new StartHere(List.of()), all, paths, p)))
                .contains("needs between 1 and 6 picks, has 0");
        assertThat(problemsOf(p -> validator.checkStartHere(new StartHere(tooMany), all, paths, p)))
                .contains("needs between 1 and 6 picks, has 7")
                .contains("INSIGHT a is listed twice");
        assertThat(problemsOf(p -> validator.checkStartHere(new StartHere(
                List.of(new StartHerePick(StartHerePick.Type.PATH, "nowhere", "Qui."))), all, paths, p)))
                .contains("PATH nowhere does not exist");
        assertThat(problemsOf(p -> validator.checkStartHere(new StartHere(
                List.of(new StartHerePick(StartHerePick.Type.PATH, "route", "Qui."),
                        new StartHerePick(StartHerePick.Type.INSIGHT, "c", "O qui."))), all, paths, p))).isEmpty();
    }
}
