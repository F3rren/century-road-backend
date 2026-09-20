package centuryroad.history.model;

/** An image hosted on Wikimedia Commons, plus the page that names its author and
 *  licence. Images from a local wiki are never turned into one of these - see
 *  WikimediaUrls for why. */
public record ImageRef(String url, int width, int height, String filePageUrl) {
}
