package centuryroad.history.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One section of the answer, and where it really came from. language is the edition that
 * supplied the items, which is not always the one asked for: fallback says so, so a
 * frontend can show "in English" instead of passing it off as Italian. stale means the
 * copy is older than the freshness window because Wikipedia could not be reached to
 * refresh it.
 */
@Schema(description = "One section of the day, and where it really came from.")
public record SectionResult(
        @Schema(description = "The edition that supplied these items. It is not always the one asked for.")
        Language language,
        @Schema(description = "True when the language asked for had nothing for this section and another one "
                + "was used. The Italian feed has no births or deaths, so those arrive in English. Say so "
                + "rather than present English text as Italian.") boolean fallback,
        @Schema(description = "True when this is a copy older than six hours because Wikipedia could not be "
                + "reached to refresh it. Old history is served in preference to an error.") boolean stale,
        @Schema(description = "The items of the section.") List<Entry> items) {

    public SectionResult {
        items = List.copyOf(items);
    }
}
