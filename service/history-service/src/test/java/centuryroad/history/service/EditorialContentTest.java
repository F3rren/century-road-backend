package centuryroad.history.service;

import centuryroad.history.model.Coordinates;
import centuryroad.history.model.DatePrecision;
import centuryroad.history.model.EventDate;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Insight;
import centuryroad.history.model.PathStop;
import centuryroad.history.model.StartHerePick;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real content, the files in src/main/resources/editorial, run through the same checks
 * the service makes at startup. This is what stops a typo in a hand-written file from becoming
 * a production that does not start: it fails here, in CI, naming the file and the problem.
 */
class EditorialContentTest {

    private final EditorialCatalog catalog = new EditorialCatalog(new PathMatchingResourcePatternResolver(),
            EditorialCatalog.DEFAULT_ROOT);

    @Test
    void theRealContentIsValid_andHasAtLeastOneCompletePath() {
        assertThat(catalog.paths()).isNotEmpty();
        assertThat(catalog.insights()).isNotEmpty();
        assertThat(catalog.startHere()).isNotEmpty();
    }

    @Test
    void everyInsightIsReachableFromTheCatalogByItsOwnSlug() {
        for (Insight insight : catalog.insights()) {
            assertThat(catalog.insight(insight.slug())).contains(insight);
        }
    }

    @Test
    void everyPathHasAReadingTimeAndStopsThatOpenRealInsights() {
        for (GuidedPath path : catalog.paths()) {
            assertThat(catalog.readingMinutes(path)).as(path.slug()).isBetween(1, 60);
            path.stops().forEach(stop -> assertThat(catalog.insight(stop.insight())).isPresent());
        }
    }

    @Test
    void theStartHereProposalsPointAtRealThings() {
        for (StartHerePick pick : catalog.startHere()) {
            if (pick.type() == StartHerePick.Type.PATH) {
                assertThat(catalog.path(pick.slug())).as(pick.slug()).isPresent();
            } else {
                assertThat(catalog.insight(pick.slug())).as(pick.slug()).isPresent();
            }
        }
    }

    // ---- lints: the mistakes the startup checks cannot see ------------------------------------
    // They live here, not in the catalog, on purpose: a lint that fails at startup is an outage,
    // one that fails in CI is a red build that names the file.

    private static final Map<String, Integer> NUMBER_WORDS = Map.of("due", 2, "tre", 3, "quattro", 4,
            "cinque", 5, "sei", 6, "sette", 7, "otto", 8, "nove", 9, "dieci", 10);

    /** "nove tappe", "otto date": a count in the prose, which stops being true when a stop is added. */
    private static final Pattern COUNT_CLAIM = Pattern.compile(
            "\\b(due|tre|quattro|cinque|sei|sette|otto|nove|dieci|\\d{1,2})\\s+(tappe|date|scoperte|momenti|viaggi)\\b",
            Pattern.CASE_INSENSITIVE);

    /** An insight is shared by several paths: its text cannot talk about the one it is read in. */
    private static final Pattern PATH_SPECIFIC = Pattern.compile(
            "\\b(in questa tappa|nel percorso|questo percorso|tappa precedente|tappa successiva)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Phrases that were true on the day they were written: they drift like "nove tappe" did. */
    private static final Pattern RELATIVE_TIME = Pattern.compile(
            "\\b(ancora oggi|attualmente|al momento|ultimi anni|prossimi anni|da (oltre|più di|circa) \\w+ anni)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Places the map's own country shapes put somewhere other than where the insight says, for a
     * reason that is the shapes' and not the insight's. Add an entry only with the reason.
     */
    private static final Map<String, String> KNOWN_COARSE_SHAPES = Map.of(
            // French Guiana is French territory: the shapes have it as part of France (FR), the insight as GF.
            "ariane-1", "FR",
            // Geneva sits on the border, and at 110 m scale the point falls on the French side.
            "vaiolo-eradicato-1980", "FR");
    private static final int MAX_SHARED_PERCENT = 60;
    private static final double NEAR_DUPLICATE_KM = 25;

    private static String texts(Insight insight) {
        return Stream.concat(Stream.of(insight.summary(), insight.before(), insight.event(), insight.after()),
                insight.notes().stream()).collect(Collectors.joining("\n"));
    }

    private static int words(String text) {
        return text.trim().split("\\s+").length;
    }

    private static List<String> matches(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void aCountWrittenInWordsMatchesTheStopsThePathReallyHas() {
        List<String> wrong = new ArrayList<>();
        for (GuidedPath path : catalog.paths()) {
            checkCounts(path.slug() + " tagline/intro", path.tagline() + " " + path.intro(), path.stops().size(), wrong);
        }
        for (StartHerePick pick : catalog.startHere()) {
            if (pick.type() == StartHerePick.Type.PATH) {
                checkCounts("start-here " + pick.slug(), pick.teaser(),
                        catalog.path(pick.slug()).orElseThrow().stops().size(), wrong);
            }
        }
        assertThat(wrong).as("a count in the prose that the stops contradict").isEmpty();
    }

    private static void checkCounts(String where, String text, int stops, List<String> wrong) {
        for (String claim : matches(COUNT_CLAIM, text)) {
            String number = claim.split("\\s+")[0].toLowerCase();
            int said = NUMBER_WORDS.containsKey(number) ? NUMBER_WORDS.get(number) : Integer.parseInt(number);
            if (said != stops) {
                wrong.add(where + ": says \"" + claim + "\" but the path has " + stops + " stops");
            }
        }
    }

    @Test
    void aSlugThatEndsInAYearSaysTheYearOfItsDate_andBeforeTheCommonEraEndsInAc() {
        Pattern bce = Pattern.compile("-(\\d+)-ac$");
        Pattern year = Pattern.compile("-(\\d{4})$");
        List<String> wrong = new ArrayList<>();
        for (Insight insight : catalog.insights()) {
            int y = insight.date().year();
            Matcher b = bce.matcher(insight.slug());
            Matcher c = year.matcher(insight.slug());
            if (y < 0 && !(b.find() && Integer.parseInt(b.group(1)) == -y)) {
                wrong.add(insight.slug() + ": year " + y + " needs a slug ending in -" + -y + "-ac");
            } else if (y > 0 && b.find()) {
                wrong.add(insight.slug() + ": ends in -ac but the year is " + y);
            } else if (y > 0 && c.find() && Integer.parseInt(c.group(1)) != y) {
                wrong.add(insight.slug() + ": the slug says " + c.group(1) + " but the year is " + y);
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void anEventBeforeTheGregorianCalendarSaysWhichCalendarOrHowSureTheDayIsInANote() {
        // 1583: the first year the weekday Intl computes can be trusted (see months.ts in the frontend).
        assertThat(catalog.insights().stream().filter(i -> i.date().year() < 1583 && i.notes().isEmpty())
                .map(Insight::slug)).as("insights before 1583 with no note on their date").isEmpty();
    }

    @Test
    void anInsightDoesNotTalkAboutThePathItIsReadIn_orAboutTheDayItWasWritten() {
        List<String> wrong = new ArrayList<>();
        for (Insight insight : catalog.insights()) {
            for (String hit : matches(PATH_SPECIFIC, texts(insight))) {
                wrong.add(insight.slug() + ": \"" + hit + "\" - an insight is shared by several paths");
            }
            for (String hit : matches(RELATIVE_TIME, texts(insight))) {
                wrong.add(insight.slug() + ": \"" + hit + "\" - true on the day it was written, not after");
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void theStopsOfAPathAreInChronologicalOrder() {
        List<String> wrong = new ArrayList<>();
        for (GuidedPath path : catalog.paths()) {
            List<Insight> stops = path.stops().stream().map(stop -> catalog.insight(stop.insight()).orElseThrow()).toList();
            // Every stop against every earlier one, not only its neighbour: "as far as both know" is not
            // transitive, so a year-only stop between two dated ones of that year would hide an inversion.
            for (int i = 0; i < stops.size(); i++) {
                for (int j = 0; j < i; j++) {
                    if (isEarlier(stops.get(i).date(), stops.get(j).date())) {
                        wrong.add(path.slug() + ": " + stops.get(i).slug() + " comes after " + stops.get(j).slug()
                                + " but is earlier");
                    }
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    /** Earlier as far as both dates know: a year-only date is not earlier than a day of that year. */
    private static boolean isEarlier(EventDate a, EventDate b) {
        DatePrecision shared = a.precision().compareTo(b.precision()) <= 0 ? a.precision() : b.precision();
        return key(a, shared) < key(b, shared);
    }

    private static long key(EventDate date, DatePrecision precision) {
        long year = date.year() * 10_000L;
        return switch (precision) {
            case YEAR -> year;
            case MONTH -> year + date.month() * 100L;
            case DAY -> year + date.month() * 100L + date.day();
        };
    }

    @Test
    void twoPathsDoNotShareMostOfTheirStops() {
        List<GuidedPath> paths = catalog.paths();
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                Set<String> shared = new HashSet<>(paths.get(i).stops().stream().map(PathStop::insight).toList());
                shared.retainAll(paths.get(j).stops().stream().map(PathStop::insight).toList());
                int smaller = Math.min(paths.get(i).stops().size(), paths.get(j).stops().size());
                if (shared.size() * 100 >= MAX_SHARED_PERCENT * smaller) {
                    wrong.add(paths.get(i).slug() + " and " + paths.get(j).slug() + " share " + shared.size()
                            + " of " + smaller + " stops");
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void noTwoInsightsAreTheSameEventWrittenTwice() {
        List<Insight> all = catalog.insights();
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Insight a = all.get(i);
                Insight b = all.get(j);
                // Only dates known to the day: two year-only ones share a placeholder, not a day.
                if (a.date().precision() == DatePrecision.DAY && a.date().equals(b.date())
                        && kilometres(a, b) <= NEAR_DUPLICATE_KM) {
                    wrong.add(a.slug() + " and " + b.slug() + ": same day, " + Math.round(kilometres(a, b)) + " km apart");
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    private static double kilometres(Insight a, Insight b) {
        double lat1 = Math.toRadians(a.place().lat());
        double lat2 = Math.toRadians(b.place().lat());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.place().lon() - a.place().lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }

    @Test
    void everyInsightIsOfTheLengthOfTheOthers_neitherAHeadlineNorAnEssay() {
        List<String> wrong = new ArrayList<>();
        for (Insight insight : catalog.insights()) {
            bound(insight, "summary", words(insight.summary()), 12, 60, wrong);
            bound(insight, "before", words(insight.before()), 20, 150, wrong);
            bound(insight, "event", words(insight.event()), 20, 150, wrong);
            bound(insight, "after", words(insight.after()), 20, 150, wrong);
            bound(insight, "before+event+after",
                    words(insight.before()) + words(insight.event()) + words(insight.after()), 90, 420, wrong);
        }
        assertThat(wrong).isEmpty();
    }

    private static void bound(Insight insight, String field, int words, int min, int max, List<String> wrong) {
        if (words < min || words > max) {
            wrong.add(insight.slug() + ": " + field + " has " + words + " words, expected " + min + " to " + max);
        }
    }

    @Test
    void aPlaceNamedInACountryIsNotInAnotherAccordingToTheMapsOwnShapes() {
        CountryLocator locator = new CountryLocator(new ObjectMapper());
        List<String> wrong = new ArrayList<>();
        for (Insight insight : catalog.insights()) {
            String code = insight.place().countryCode();
            if (code == null) {
                continue;
            }
            // Nothing found is fine: the shapes are coarse (110 m scale) and leave out the sea and small states.
            locator.locate(new Coordinates(insight.place().lat(), insight.place().lon()))
                    .filter(found -> !found.equals(code))
                    .filter(found -> !found.equals(KNOWN_COARSE_SHAPES.get(insight.slug())))
                    .ifPresent(found -> wrong.add(insight.slug() + ": countryCode " + code + " but the point is in " + found));
        }
        assertThat(wrong).isEmpty();
    }
}