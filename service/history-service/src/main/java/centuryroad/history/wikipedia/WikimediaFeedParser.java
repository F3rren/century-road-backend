package centuryroad.history.wikipedia;

import centuryroad.history.exception.UpstreamBadResponseException;
import centuryroad.history.model.Coordinates;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Entry;
import centuryroad.history.model.ImageRef;
import centuryroad.history.model.Language;
import centuryroad.history.model.PageRef;
import centuryroad.history.model.Section;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.MonthDay;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns Wikipedia's JSON into a DayFeed, tolerating what the feed actually does rather than
 * what a schema would say. Observed on the live API: a section with nothing in it can be
 * [] or {} (the same response mixes both), a holiday has no year, an entry can have no
 * pages, and a page can lack a thumbnail or an extract. None of that is an error.
 *
 * Deliberately drops what the service has no use for or should not forward: the HTML
 * variants (extract_html, displaytitle), which would put markup in front of a frontend, and
 * the several kilobytes of revision metadata per page. That is what takes a 620 KB response
 * down to something worth caching.
 */
final class WikimediaFeedParser {

    private static final double MAX_LATITUDE = 90;
    private static final double MAX_LONGITUDE = 180;

    /** Q, then digits. Real ids have at most nine of them today; twelve leaves room to grow. */
    private static final Pattern WIKIDATA_ID = Pattern.compile("Q[0-9]{1,12}");

    private WikimediaFeedParser() {
    }

    static DayFeed parse(JsonNode root, Language language, MonthDay day) {
        if (root == null || !root.isObject()) {
            throw new UpstreamBadResponseException("Feed body is not a JSON object");
        }
        Map<Section, List<Entry>> sections = new EnumMap<>(Section.class);
        for (Section section : Section.values()) {
            sections.put(section, parseEntries(root.get(section.key())));
        }
        return new DayFeed(language, day, sections);
    }

    private static List<Entry> parseEntries(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode item : array) {
            parseEntry(item).ifPresent(entries::add);
        }
        return entries;
    }

    private static Optional<Entry> parseEntry(JsonNode item) {
        String text = text(item, "text");
        if (text == null) {
            return Optional.empty();
        }
        JsonNode yearNode = item.path("year");
        Integer year = yearNode.canConvertToInt() && yearNode.isNumber() ? yearNode.intValue() : null;
        List<PageRef> pages = new ArrayList<>();
        JsonNode pagesNode = item.path("pages");
        if (pagesNode.isArray()) {
            for (JsonNode page : pagesNode) {
                parsePage(page).ifPresent(pages::add);
            }
        }
        return Optional.of(new Entry(text, year, pages));
    }

    private static Optional<PageRef> parsePage(JsonNode page) {
        String title = firstNonNull(text(page, "normalizedtitle"),
                text(page.path("titles"), "normalized"),
                underscoresToSpaces(text(page, "title")));
        String url = text(page.path("content_urls").path("desktop"), "page");
        if (title == null || url == null || !WikimediaUrls.isWikimediaHttps(url)) {
            return Optional.empty();
        }
        return Optional.of(new PageRef(title, text(page, "description"), text(page, "extract"), url,
                parseImage(page.path("thumbnail")), parseImage(page.path("originalimage")),
                parseCoordinates(page.path("coordinates")), parseWikidataId(page)));
    }

    /** Thumbnail and original go through the same rule: Commons only, with its file page. */
    private static ImageRef parseImage(JsonNode image) {
        String source = text(image, "source");
        if (source == null || !WikimediaUrls.isWikimediaHttps(source)) {
            return null;
        }
        return WikimediaUrls.commonsFilePage(source)
                .map(filePage -> new ImageRef(source, image.path("width").asInt(0),
                        image.path("height").asInt(0), filePage))
                .orElse(null);
    }

    /** The feed sends whole numbers as integers ({"lat": 57}), so read any number. Anything
     *  that is not a place on Earth is dropped rather than passed on for a map to plot. */
    private static Coordinates parseCoordinates(JsonNode coordinates) {
        JsonNode lat = coordinates.path("lat");
        JsonNode lon = coordinates.path("lon");
        if (!lat.isNumber() || !lon.isNumber()) {
            return null;
        }
        double latitude = lat.doubleValue();
        double longitude = lon.doubleValue();
        boolean isOnEarth = Math.abs(latitude) <= MAX_LATITUDE && Math.abs(longitude) <= MAX_LONGITUDE;
        return isOnEarth ? new Coordinates(latitude, longitude) : null;
    }

    /** A frontend may build a link from this, so it is only passed on if it is a Wikidata id. */
    private static String parseWikidataId(JsonNode page) {
        String id = text(page, "wikibase_item");
        return id != null && WIKIDATA_ID.matcher(id).matches() ? id : null;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return node.isTextual() && !node.asText().isBlank() ? node.asText().strip() : null;
    }

    private static String underscoresToSpaces(String value) {
        return value == null ? null : value.replace('_', ' ');
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
