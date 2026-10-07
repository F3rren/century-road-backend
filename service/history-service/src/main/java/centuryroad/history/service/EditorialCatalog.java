package centuryroad.history.service;

import centuryroad.history.model.Cover;
import centuryroad.history.model.DatePrecision;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Insight;
import centuryroad.history.model.InsightLink;
import centuryroad.history.model.PathStop;
import centuryroad.history.model.Place;
import centuryroad.history.model.Source;
import centuryroad.history.model.StartHere;
import centuryroad.history.model.StartHerePick;
import centuryroad.history.model.Topic;
import centuryroad.history.wikipedia.WikimediaUrls;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The hand-written content: guided paths, "Perché conta" insights and the "Inizia da qui"
 * proposals. It lives as JSON in src/main/resources/editorial, in the repository, next to
 * the code that serves it - reviewed in a pull request like everything else, with no editor
 * and no table to keep in step with it.
 *
 * Everything is read and checked once, at startup, and a mistake stops the service with
 * every problem listed (a typo in a key, a stop that opens an insight that does not exist,
 * a path with three stops), the way a bad setting does. EditorialContentTest runs the same
 * checks on the real files in CI, so the mistake is found before a deploy and not by it.
 *
 * Immutable afterwards: a change of content is a change of code, and a new release.
 */
@Component
public class EditorialCatalog {

    static final String DEFAULT_ROOT = "classpath:editorial";

    /** A path is a small itinerary: shorter is a list, longer is a course. */
    static final int MIN_STOPS = 6;
    static final int MAX_STOPS = 10;
    /** "Collegamenti": two or three events to read next, not a menu. */
    static final int MIN_LINKS = 2;
    static final int MAX_LINKS = 3;
    static final int MAX_PICKS = 6;
    /** The reading speed the estimate assumes, for Italian prose. */
    static final int WORDS_PER_MINUTE = 200;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

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
            @Value("${history.editorial.root:" + DEFAULT_ROOT + "}") String root) {
        // Strict on purpose: Spring's own mapper ignores keys it does not know, which is how a
        // "befor" in a hand-written file would become an insight with no past. A number where an enum
        // belongs is refused too: it would be read as the value's position in the declaration.
        JsonMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .build();
        List<String> problems = new ArrayList<>();

        Map<String, Insight> loadedInsights = new LinkedHashMap<>();
        for (Loaded<Insight> loaded : load(resolver, mapper, root + "/insights/*.json", Insight.class, problems)) {
            Insight insight = loaded.value();
            // Without this, a file with no "slug" is skipped below and never checked at all.
            if (insight.slug() == null) {
                problems.add(loaded.file() + ": the insight has no slug");
            }
            checkSlugMatchesFile(loaded.file(), insight.slug(), problems);
            if (insight.slug() != null && loadedInsights.putIfAbsent(insight.slug(), insight) != null) {
                problems.add(loaded.file() + ": the slug " + insight.slug() + " is already used by another insight");
            }
        }
        // Checked once all are in, because an insight's links point at the others.
        loadedInsights.values().forEach(insight -> checkInsight(insight, loadedInsights, problems));

        Map<String, GuidedPath> loadedPaths = new LinkedHashMap<>();
        for (Loaded<GuidedPath> loaded : load(resolver, mapper, root + "/paths/*.json", GuidedPath.class, problems)) {
            GuidedPath path = checkPath(loaded.file(), loaded.value(), loadedInsights, problems);
            if (path.slug() != null && loadedPaths.putIfAbsent(path.slug(), path) != null) {
                problems.add(loaded.file() + ": the slug " + path.slug() + " is already used by another path");
            }
        }

        List<StartHerePick> picks = loadStartHere(resolver, mapper, root + "/start-here.json", loadedInsights,
                loadedPaths, problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException("The editorial content is not valid:\n - " + String.join("\n - ", problems));
        }

        this.insights = sortedByDate(loadedInsights);
        this.paths = sortedByTopic(loadedPaths, loadedInsights);
        this.startHere = List.copyOf(picks);
    }

    // ---- reading ---------------------------------------------------------------------------

    /**
     * Every path, grouped by topic in the order the topics are declared (see {@link Topic}), and
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

    // ---- loading ---------------------------------------------------------------------------

    private record Loaded<T>(String file, T value) {
    }

    private static <T> List<Loaded<T>> load(ResourcePatternResolver resolver, JsonMapper mapper, String pattern,
            Class<T> type, List<String> problems) {
        List<Loaded<T>> loaded = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            problems.add(pattern + ": cannot be listed (" + e.getMessage() + ")");
            return loaded;
        }
        Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
        for (Resource resource : resources) {
            String file = String.valueOf(resource.getFilename());
            try (InputStream in = resource.getInputStream()) {
                T value = mapper.readValue(in, type);
                if (value == null) {
                    problems.add(file + ": is empty");
                } else {
                    loaded.add(new Loaded<>(file, value));
                }
            } catch (IOException e) {
                problems.add(file + ": cannot be read (" + e.getMessage() + ")");
            }
        }
        return loaded;
    }

    private static List<StartHerePick> loadStartHere(ResourcePatternResolver resolver, JsonMapper mapper,
            String location, Map<String, Insight> insights, Map<String, GuidedPath> paths, List<String> problems) {
        Resource resource = resolver.getResource(location);
        if (!resource.exists()) {
            problems.add("start-here.json: missing");
            return List.of();
        }
        StartHere file;
        try (InputStream in = resource.getInputStream()) {
            file = mapper.readValue(in, StartHere.class);
            if (file == null) {
                problems.add("start-here.json: is empty");
                return List.of();
            }
        } catch (IOException e) {
            problems.add("start-here.json: cannot be read (" + e.getMessage() + ")");
            return List.of();
        }
        if (file.picks().isEmpty() || file.picks().size() > MAX_PICKS) {
            problems.add("start-here.json: needs between 1 and " + MAX_PICKS + " picks, has " + file.picks().size());
        }
        Set<String> seen = new HashSet<>();
        for (StartHerePick pick : file.picks()) {
            if (pick.type() == null || isBlank(pick.slug()) || isBlank(pick.teaser())) {
                problems.add("start-here.json: every pick needs a type, a slug and a teaser");
                continue;
            }
            boolean exists = pick.type() == StartHerePick.Type.PATH ? paths.containsKey(pick.slug())
                    : insights.containsKey(pick.slug());
            if (!exists) {
                problems.add("start-here.json: " + pick.type() + " " + pick.slug() + " does not exist");
            }
            if (!seen.add(pick.type() + ":" + pick.slug())) {
                problems.add("start-here.json: " + pick.type() + " " + pick.slug() + " is listed twice");
            }
        }
        return file.picks();
    }

    // ---- checking --------------------------------------------------------------------------

    private static void checkSlugMatchesFile(String file, String slug, List<String> problems) {
        if (slug != null && !file.equals(slug + ".json")) {
            problems.add(file + ": the slug is " + slug + ", so the file must be called " + slug + ".json");
        }
    }

    private static void checkInsight(Insight in, Map<String, Insight> all, List<String> problems) {
        String at = "insight " + in.slug() + ": ";
        if (in.slug() == null || !SLUG.matcher(in.slug()).matches()) {
            problems.add("insight " + in.slug() + ": the slug must be lowercase words joined by hyphens");
        }
        requireText(at, "title", in.title(), problems);
        requireText(at, "summary", in.summary(), problems);
        requireText(at, "before", in.before(), problems);
        requireText(at, "event", in.event(), problems);
        requireText(at, "after", in.after(), problems);
        if (in.date() == null) {
            problems.add(at + "date is missing");
        } else if (in.date().monthDay().isEmpty() || in.date().year() == 0
                || in.date().year() < -9999 || in.date().year() > 9999) {
            problems.add(at + "date is not a real date (a year other than 0 between -9999 and 9999, a month and a day)");
        } else if (in.date().precision() == DatePrecision.YEAR && (in.date().month() != 1 || in.date().day() != 1)) {
            problems.add(at + "a date known only by its year is written as 1 January (month 1, day 1)");
        } else if (in.date().precision() == DatePrecision.MONTH && in.date().day() != 1) {
            problems.add(at + "a date known only by its month is written as the 1st of that month (day 1)");
        }
        checkPlace(at, in.place(), problems);

        if (in.links().size() < MIN_LINKS || in.links().size() > MAX_LINKS) {
            problems.add(at + "needs " + MIN_LINKS + " to " + MAX_LINKS + " links, has " + in.links().size());
        }
        Set<String> linked = new HashSet<>();
        for (InsightLink link : in.links()) {
            if (link.slug() == null || !all.containsKey(link.slug())) {
                problems.add(at + "links to " + link.slug() + ", which does not exist");
            } else if (link.slug().equals(in.slug())) {
                problems.add(at + "links to itself");
            } else if (!linked.add(link.slug())) {
                problems.add(at + "links to " + link.slug() + " twice");
            }
            if (isBlank(link.reason())) {
                problems.add(at + "the link to " + link.slug() + " has no reason");
            }
        }

        if (in.sources().isEmpty()) {
            problems.add(at + "needs at least one source");
        }
        for (Source source : in.sources()) {
            if (isBlank(source.title()) || isBlank(source.publisher()) || !isHttps(source.url())) {
                problems.add(at + "every source needs a title, a publisher and an https url (got " + source.url() + ")");
            }
        }
        if (in.provenance() == null || isBlank(in.provenance().author())) {
            problems.add(at + "provenance.author is missing");
        } else if (in.provenance().reviewedAt() != null && in.provenance().reviewedAt().isAfter(LocalDate.now(ZoneOffset.UTC))) {
            problems.add(at + "provenance.reviewedAt is in the future: it is the day somebody really reviewed the text");
        }
    }

    private static void checkPlace(String at, Place place, List<String> problems) {
        if (place == null) {
            problems.add(at + "place is missing");
            return;
        }
        if (isBlank(place.name())) {
            problems.add(at + "place.name is missing");
        }
        boolean outOfRange = place.lat() < -90 || place.lat() > 90 || place.lon() < -180 || place.lon() > 180;
        // 0,0 is what a missing coordinate deserializes to, and it is open sea.
        if (outOfRange || (place.lat() == 0 && place.lon() == 0)) {
            problems.add(at + "place has no usable coordinates (" + place.lat() + ", " + place.lon() + ")");
        }
        if (place.countryCode() != null && !COUNTRY_CODE.matcher(place.countryCode()).matches()) {
            problems.add(at + "place.countryCode must be two uppercase letters, got " + place.countryCode());
        }
        if (place.approximate() != !isBlank(place.note())) {
            problems.add(at + "place.approximate and place.note go together: an approximate pin must say why, "
                    + "and a note means the pin is approximate");
        }
    }

    private GuidedPath checkPath(String file, GuidedPath path, Map<String, Insight> insights, List<String> problems) {
        String at = "path " + path.slug() + ": ";
        if (path.slug() == null || !SLUG.matcher(path.slug()).matches()) {
            problems.add(file + ": the slug must be lowercase words joined by hyphens, got " + path.slug());
        }
        checkSlugMatchesFile(file, path.slug(), problems);
        requireText(at, "title", path.title(), problems);
        requireText(at, "tagline", path.tagline(), problems);
        requireText(at, "intro", path.intro(), problems);
        // A name that is not in the list is already refused when the file is read.
        if (path.topic() == null) {
            problems.add(at + "topic is missing: it must be one of " + Arrays.toString(Topic.values()));
        }
        if (path.stops().size() < MIN_STOPS || path.stops().size() > MAX_STOPS) {
            problems.add(at + "needs " + MIN_STOPS + " to " + MAX_STOPS + " stops, has " + path.stops().size());
        }
        Set<String> opened = new HashSet<>();
        for (PathStop stop : path.stops()) {
            if (stop.insight() == null || !insights.containsKey(stop.insight())) {
                problems.add(at + "a stop opens " + stop.insight() + ", which does not exist");
            } else if (!opened.add(stop.insight())) {
                problems.add(at + "opens " + stop.insight() + " twice");
            }
            if (isBlank(stop.narrative())) {
                problems.add(at + "the stop on " + stop.insight() + " has no narrative");
            }
        }
        Cover cover = path.cover();
        if (cover == null) {
            return path;
        }
        Optional<String> filePage = cover.imageUrl() == null ? Optional.empty()
                : WikimediaUrls.commonsFilePage(cover.imageUrl());
        if (filePage.isEmpty()) {
            problems.add(at + "the cover must be an image hosted on Wikimedia Commons (upload.wikimedia.org), got "
                    + cover.imageUrl());
        }
        if (isBlank(cover.alt())) {
            problems.add(at + "the cover needs a description (alt)");
        }
        // The file page is derived from the image, never written by hand, so the two cannot disagree.
        return path.withCover(new Cover(cover.imageUrl(), filePage.orElse(null), cover.alt()));
    }

    private static void requireText(String at, String field, String value, List<String> problems) {
        if (isBlank(value)) {
            problems.add(at + field + " is missing");
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static boolean isHttps(String url) {
        try {
            URI uri = URI.create(String.valueOf(url));
            return "https".equals(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

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
                .sorted(Comparator.comparingInt((GuidedPath p) -> p.topic().ordinal())
                        .thenComparingInt(p -> yearsOf(p, insights).min())
                        .thenComparing(GuidedPath::slug))
                .forEach(path -> sorted.put(path.slug(), path));
        return sorted;
    }
}
