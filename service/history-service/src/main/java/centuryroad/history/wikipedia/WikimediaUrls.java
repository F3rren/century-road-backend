package centuryroad.history.wikipedia;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Judgements about URLs that arrive inside Wikipedia's answer. Nothing in a response is
 * passed on as a link or an image without going through here first.
 */
final class WikimediaUrls {

    /** Where the feed's images live: originals on upload, and most thumbnails on thumb. Both
     *  serve the same /wikipedia/commons/... paths. */
    private static final Set<String> IMAGE_HOSTS = Set.of("upload.wikimedia.org", "thumb.wikimedia.org");
    private static final String COMMONS_FILE_PAGE = "https://commons.wikimedia.org/wiki/File:";

    /** /wikipedia/commons/a/ab/Name.jpg and /wikipedia/commons/thumb/a/ab/Name.jpg/330px-Name.jpg */
    private static final Pattern COMMONS_PATH =
            Pattern.compile("^/wikipedia/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/([^/]+)(?:/[^/]+)?$");

    private WikimediaUrls() {
    }

    /** True for an https link on a Wikipedia or Wikimedia host. Defensive: the values come
     *  from Wikipedia, but a frontend will render them as links. */
    static boolean isWikimediaHttps(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && (host.endsWith(".wikipedia.org") || host.endsWith(".wikimedia.org"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The Commons page for an image, which is where its author and licence are written.
     * Empty for anything not hosted on Commons - and that is the point: the feed also
     * serves images uploaded to a single wiki (/wikipedia/it/...), which is where
     * non-free "fair use" pictures live, and nothing in the response says which is which.
     * Commons only accepts freely licensed files, so an image that is there can be shown
     * with a link to its file page; an image that is not is left out.
     */
    static Optional<String> commonsFilePage(String imageUrl) {
        try {
            URI uri = URI.create(imageUrl);
            String host = uri.getHost();
            if (host == null || !IMAGE_HOSTS.contains(host) || uri.getRawPath() == null) {
                return Optional.empty();
            }
            Matcher matcher = COMMONS_PATH.matcher(uri.getRawPath());
            return matcher.matches() ? Optional.of(COMMONS_FILE_PAGE + matcher.group(1)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
