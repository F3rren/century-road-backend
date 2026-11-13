package centuryroad.history.service;

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
import centuryroad.history.service.EditorialLoader.Loaded;
import centuryroad.history.wikipedia.WikimediaUrls;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The rules the hand-written content has to satisfy, and nothing but the rules: it reads no file
 * and keeps no state, it takes what was read and reports what is wrong with it. That is what
 * makes each rule testable on a few lines of Java (see EditorialValidatorTest) and not only
 * through a directory of JSON, and what keeps a new rule from touching how files are found.
 *
 * Everything found is recorded in the {@link EditorialProblems} it is handed, never thrown, so
 * one pass reports every mistake.
 *
 * The clock is a parameter for the one rule that depends on today: a review date cannot be in the
 * future.
 */
final class EditorialValidator {

    /** A path is a small itinerary: shorter is a list, longer is a course. */
    static final int MIN_STOPS = 6;
    static final int MAX_STOPS = 10;
    /** "Collegamenti": two or three events to read next, not a menu. */
    static final int MIN_LINKS = 2;
    static final int MAX_LINKS = 3;
    static final int MAX_PICKS = 6;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    /** The insights that were read, by slug in the order read, and the topic folder each was found in. */
    record IndexedInsights(Map<String, Insight> bySlug, Map<String, String> folderBySlug) {
    }

    private final Clock clock;

    EditorialValidator(Clock clock) {
        this.clock = clock;
    }

    // ---- insights --------------------------------------------------------------------------

    /**
     * Indexes the insights by slug, reporting a missing or repeated slug and a file whose name is not
     * its slug, then checks each one: they are checked once all are in, because an insight's links
     * point at the others.
     */
    IndexedInsights indexInsights(List<Loaded<Insight>> loaded, EditorialProblems problems) {
        Map<String, Insight> bySlug = new LinkedHashMap<>();
        Map<String, String> folderBySlug = new HashMap<>();
        for (Loaded<Insight> file : loaded) {
            Insight insight = file.value();
            // Without this, a file with no "slug" is skipped below and never checked at all.
            if (insight.slug() == null) {
                problems.add(file.file() + ": the insight has no slug");
            }
            checkSlugMatchesFile(file.file(), insight.slug(), problems);
            if (insight.slug() != null && bySlug.putIfAbsent(insight.slug(), insight) != null) {
                problems.add(file.where() + ": the slug " + insight.slug() + " is already used by another insight");
            } else if (insight.slug() != null) {
                folderBySlug.put(insight.slug(), file.folder());
            }
        }
        bySlug.values().forEach(insight -> checkInsight(insight, bySlug, problems));
        return new IndexedInsights(bySlug, folderBySlug);
    }

    void checkInsight(Insight in, Map<String, Insight> all, EditorialProblems problems) {
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
        } else if (in.provenance().reviewedAt() != null && in.provenance().reviewedAt().isAfter(today())) {
            problems.add(at + "provenance.reviewedAt is in the future: it is the day somebody really reviewed the text");
        }
    }

    private void checkPlace(String at, Place place, EditorialProblems problems) {
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

    // ---- paths -----------------------------------------------------------------------------

    /** Indexes the paths by slug, reporting a repeated slug and a file in the wrong topic folder, and checks each one. */
    Map<String, GuidedPath> indexPaths(List<Loaded<GuidedPath>> loaded, Map<String, Insight> insights,
            EditorialProblems problems) {
        Map<String, GuidedPath> bySlug = new LinkedHashMap<>();
        for (Loaded<GuidedPath> file : loaded) {
            GuidedPath path = file.value();
            checkPath(file.file(), path, insights, problems);
            if (path.topic() != null && !path.topic().folder().equals(file.folder())) {
                problems.add(file.where() + ": the path is about " + path.topic() + ", so the file goes in the folder "
                        + path.topic().folder());
            }
            if (path.slug() != null && bySlug.putIfAbsent(path.slug(), path) != null) {
                problems.add(file.where() + ": the slug " + path.slug() + " is already used by another path");
            }
        }
        return bySlug;
    }

    void checkPath(String file, GuidedPath path, Map<String, Insight> insights, EditorialProblems problems) {
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
        if (path.cover() == null) {
            return;
        }
        if (path.cover().imageUrl() == null || WikimediaUrls.commonsFilePage(path.cover().imageUrl()).isEmpty()) {
            problems.add(at + "the cover must be an image hosted on Wikimedia Commons (upload.wikimedia.org), got "
                    + path.cover().imageUrl());
        }
        if (isBlank(path.cover().alt())) {
            problems.add(at + "the cover needs a description (alt)");
        }
    }

    /**
     * An insight is filed under a topic folder, and under one of the topics of the paths that open
     * it - an insight shared by two periods lives with the earlier one, but never in a folder that
     * none of its paths is about. One that no path opens only needs a topic folder.
     */
    void checkInsightFolders(Map<String, String> insightFolders, Map<String, GuidedPath> paths,
            EditorialProblems problems) {
        Map<String, Set<String>> foldersOfPaths = new HashMap<>();
        for (GuidedPath path : paths.values()) {
            if (path.topic() == null) {
                continue;
            }
            for (PathStop stop : path.stops()) {
                foldersOfPaths.computeIfAbsent(stop.insight(), slug -> new TreeSet<>()).add(path.topic().folder());
            }
        }
        insightFolders.forEach((slug, folder) -> {
            String where = folder + "/" + slug + ".json";
            if (Topic.ofFolder(folder).isEmpty()) {
                problems.add(where + ": " + folder + " is not a topic folder, the insight goes in one of "
                        + Arrays.stream(Topic.values()).map(Topic::folder).toList());
                return;
            }
            Set<String> wanted = foldersOfPaths.get(slug);
            if (wanted != null && !wanted.contains(folder)) {
                problems.add(where + ": the insight is opened by paths about " + wanted + ", so the file goes in the "
                        + "folder of one of them");
            }
        });
    }

    // ---- "Inizia da qui" -------------------------------------------------------------------

    void checkStartHere(StartHere file, Map<String, Insight> insights, Map<String, GuidedPath> paths,
            EditorialProblems problems) {
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
    }

    // ---- shared pieces ---------------------------------------------------------------------

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static void checkSlugMatchesFile(String file, String slug, EditorialProblems problems) {
        if (slug != null && !file.equals(slug + ".json")) {
            problems.add(file + ": the slug is " + slug + ", so the file must be called " + slug + ".json");
        }
    }

    private static void requireText(String at, String field, String value, EditorialProblems problems) {
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
}
