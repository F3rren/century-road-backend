package centuryroad.history.wikipedia;

import centuryroad.history.exception.UpstreamBadResponseException;
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
                parseThumbnail(page.path("thumbnail"))));
    }

    private static ImageRef parseThumbnail(JsonNode thumbnail) {
        String source = text(thumbnail, "source");
        if (source == null || !WikimediaUrls.isWikimediaHttps(source)) {
            return null;
        }
        return WikimediaUrls.commonsFilePage(source)
                .map(filePage -> new ImageRef(source, thumbnail.path("width").asInt(0),
                        thumbnail.path("height").asInt(0), filePage))
                .orElse(null);
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
