package centuryroad.history.model;

import java.util.List;

/**
 * One section of the answer, and where it really came from. language is the edition that
 * supplied the items, which is not always the one asked for: fallback says so, so a
 * frontend can show "in English" instead of passing it off as Italian. stale means the
 * copy is older than the freshness window because Wikipedia could not be reached to
 * refresh it.
 */
public record SectionResult(Language language, boolean fallback, boolean stale, List<Entry> items) {

    public SectionResult {
        items = List.copyOf(items);
    }
}
