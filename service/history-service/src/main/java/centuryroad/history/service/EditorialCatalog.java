package centuryroad.history.service;

import centuryroad.history.model.Cover;
import centuryroad.history.model.DatePrecision;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Insight;
import centuryroad.history.model.PathStop;
import centuryroad.history.model.StartHere;
import centuryroad.history.model.StartHerePick;
import centuryroad.history.wikipedia.WikimediaUrls;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.MonthDay;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The hand-written content: guided paths, "Perché conta" insights and the "Inizia da qui"
 * proposals. It lives as JSON in src/main/resources/editorial, in the repository, next to
 * the code that serves it - reviewed in a pull request like everything else, with no editor
 * and no table to keep in step with it.
 *
 * One folder per topic, named after it (roma-repubblicana, medioevo...; see {@link centuryroad.history.model.Topic#folder()}),
 * each with its paths in paths/ and its insights in insights/, so what a folder tells is what
 * the files in it are about. A path sits in the folder of its own topic; an insight sits in the
 * folder of a topic of one of the paths that open it (the earliest one, when it serves several).
 * "Inizia da qui" is a single file at the root. A file in the wrong folder is a startup error.
 *
 * Everything is read and checked once, at startup, and a mistake stops the service with
 * every problem listed (a typo in a key, a stop that opens an insight that does not exist,
 * a path with three stops), the way a bad setting does. EditorialContentTest runs the same
 * checks on the real files in CI, so the mistake is found before a deploy and not by it.
 *
 * This class is what is left once the content is known to be good: the in-memory index and the
 * questions asked of it. Finding and parsing the files is {@link EditorialLoader}'s job, the rules
 * they must satisfy are {@link EditorialValidator}'s, so each can change - and be tested - alone.
 *
 * Immutable afterwards: a change of content is a change of code, and a new release.
 */
@Component
public class EditorialCatalog {

    static final String DEFAULT_ROOT = "classpath:editorial";

    /** The reading speed the estimate assumes, for Italian prose. */
    static final int WORDS_PER_MINUTE = 200;

    private final Map<String, Insight> insights;
    private final Map<String, GuidedPath> paths;
    private final List<StartHerePick> startHere;

    /**
     * The root is not a setting to change in production: it exists so tests can serve a small
     * fixed set of content instead of the real one, which changes whenever an editor does,
     * and so a test can point the catalog at deliberately broken content.
     */
    @Autowired
    public EditorialCatalog(ResourcePatternResolver resolver,
            @Value("${history.editorial.root:" + DEFAULT_ROOT + "}") String root, Clock clock) {
        EditorialProblems problems = new EditorialProblems();
        EditorialLoader loader = new EditorialLoader(resolver);
        EditorialValidator validator = new EditorialValidator(clock);

        // Each step reports into the same list, in this order, so one failure shows every problem.
        EditorialValidator.IndexedInsights indexed = validator.indexInsights(
                loader.loadAll(root + "/*/insights/*.json", Insight.class, problems), problems);
        Map<String, GuidedPath> loadedPaths = validator.indexPaths(
                loader.loadAll(root + "/*/paths/*.json", GuidedPath.class, problems), indexed.bySlug(), problems);
        validator.checkInsightFolders(indexed.folderBySlug(), loadedPaths, problems);

        Optional<StartHere> picks = loader.loadStartHere(root + "/start-here.json", problems);
        picks.ifPresent(file -> validator.checkStartHere(file, indexed.bySlug(), loadedPaths, problems));

        problems.failIfAny();

        this.insights = sortedByDate(indexed.bySlug());
        this.paths = sortedByTopic(loadedPaths, indexed.bySlug());
        this.startHere = picks.map(StartHere::picks).orElse(List.of());
    }

    // ---- reading ---------------------------------------------------------------------------

    /**
     * Every path, grouped by topic in the order the topics are declared (see {@link centuryroad.history.model.Topic}), and
     * inside a topic from the oldest to the newest, by the year of its first stop, then by slug.
     */
    public List<GuidedPath> paths() {
        return List.copyOf(paths.values());
    }

    public Optional<GuidedPath> path(String slug) {
        return Optional.ofNullable(paths.get(slug));
    }

    /** Every insight, oldest first. */
    public List<Insight> insights() {
        return List.copyOf(insights.values());
    }

    public Optional<Insight> insight(String slug) {
        return Optional.ofNullable(insights.get(slug));
    }

    /**
     * The insights that belong to this day of the year, whatever the year, oldest first. Only those
     * known to the day: one with just a year has a placeholder month and day, which belong to no day.
     */
    public List<Insight> insightsOn(MonthDay day) {
        return insights.values().stream()
                .filter(insight -> insight.date().precision() == DatePrecision.DAY)
                .filter(insight -> insight.date().monthDay().filter(day::equals).isPresent())
                .toList();
    }

    /** The paths that have a stop on this insight. */
    public List<GuidedPath> pathsContaining(String insightSlug) {
        return paths.values().stream()
                .filter(path -> path.stops().stream().anyMatch(stop -> stop.insight().equals(insightSlug)))
                .toList();
    }

    public List<StartHerePick> startHere() {
        return startHere;
    }

    /**
     * Minutes to read a whole path - introduction, the line before each stop and every stop's
     * insight - at {@value #WORDS_PER_MINUTE} words a minute, rounded up, never below one.
     * Counted, not written, so it cannot drift from the text.
     */
    public int readingMinutes(GuidedPath path) {
        long words = words(path.tagline()) + words(path.intro());
        for (PathStop stop : path.stops()) {
            Insight insight = insights.get(stop.insight());
            words += words(stop.narrative()) + words(insight.summary()) + words(insight.before())
                    + words(insight.event()) + words(insight.after());
        }
        return (int) Math.max(1, Math.ceil(words / (double) WORDS_PER_MINUTE));
    }

    /** The year of the path's earliest stop: negative before the common era. Read, not written. */
    public int startYear(GuidedPath path) {
        return yearsOf(path, insights).min();
    }

    /** The year of the path's latest stop. */
    public int endYear(GuidedPath path) {
        return yearsOf(path, insights).max();
    }

    private record Years(int min, int max) {
    }

    /** The span of the stops' dates. Only meaningful once every stop is known to open a real insight. */
    private static Years yearsOf(GuidedPath path, Map<String, Insight> insights) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (PathStop stop : path.stops()) {
            int year = insights.get(stop.insight()).date().year();
            min = Math.min(min, year);
            max = Math.max(max, year);
        }
        return new Years(min, max);
    }

    private static long words(String text) {
        return text == null || text.isBlank() ? 0 : Arrays.stream(text.trim().split("\\s+")).count();
    }

    // ---- indexing --------------------------------------------------------------------------

    private static Map<String, Insight> sortedByDate(Map<String, Insight> source) {
        Map<String, Insight> sorted = new LinkedHashMap<>();
        source.values().stream()
                .sorted(Comparator.comparingInt((Insight i) -> i.date().year())
                        .thenComparingInt(i -> i.date().month())
                        .thenComparingInt(i -> i.date().day())
                        .thenComparing(Insight::slug))
                .forEach(insight -> sorted.put(insight.slug(), insight));
        return sorted;
    }

    /** Topic as declared, then the year of the first stop, then slug: the order a reader browses in. */
    private static Map<String, GuidedPath> sortedByTopic(Map<String, GuidedPath> source,
            Map<String, Insight> insights) {
        Map<String, GuidedPath> sorted = new LinkedHashMap<>();
        source.values().stream()
                .map(EditorialCatalog::withDerivedFilePage)
                .sorted(Comparator.comparingInt((GuidedPath p) -> p.topic().ordinal())
                        .thenComparingInt(p -> yearsOf(p, insights).min())
                        .thenComparing(GuidedPath::slug))
                .forEach(path -> sorted.put(path.slug(), path));
        return sorted;
    }

    /**
     * The Commons page of a cover is derived from its image, never written by hand, so the two
     * cannot disagree.
     */
    private static GuidedPath withDerivedFilePage(GuidedPath path) {
        Cover cover = path.cover();
        if (cover == null) {
            return path;
        }
        String filePage = cover.imageUrl() == null ? null : WikimediaUrls.commonsFilePage(cover.imageUrl()).orElse(null);
        return path.withCover(new Cover(cover.imageUrl(), filePage, cover.alt()));
    }
}
