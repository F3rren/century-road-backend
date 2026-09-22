package centuryroad.history.dto;

import centuryroad.history.model.Language;
import centuryroad.history.model.OnThisDayResult;
import centuryroad.history.model.Section;
import centuryroad.history.model.SectionResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What goes over the wire for one day. Sections are keyed by their Wikipedia name and appear
 * in a fixed order. Each carries the language it really came from, so the top-level
 * language is what was asked for, not necessarily what every section says.
 *
 * The attribution block is not decoration: Wikipedia's text is CC BY-SA 4.0, which requires
 * crediting the source and naming the licence wherever it is shown. The per-page url is the
 * link back to the article; this is the licence notice to go with it.
 */
@Schema(description = "What happened on one calendar day.")
public record OnThisDayResponse(
        @Schema(description = "The day that was asked for.") DateDto date,
        @Schema(description = "The language that was asked for. Each section says which one it really came from.")
        Language language,
        @Schema(description = "The credit Wikipedia's licence requires wherever its text is shown.")
        Attribution attribution,
        @Schema(description = "The sections asked for, keyed by name: selected, events, births, deaths, holidays. "
                + "Always in that order.") Map<String, SectionResult> sections,
        @Schema(description = "Things worth telling the user about how this answer was put together. "
                + "PRIMARY_UNAVAILABLE: the language asked for could not be fetched and every section comes from "
                + "the fallback. FALLBACK_UNAVAILABLE: a gap in the language asked for could not be filled.",
                example = "[]") List<String> warnings) {

    @Schema(description = "A calendar day, without a year.")
    public record DateDto(
            @Schema(description = "Month, 1 to 12.", example = "10") int month,
            @Schema(description = "Day of the month.", example = "16") int day) {
    }

    @Schema(description = "Where the text comes from and under what licence. Show it with the text.")
    public record Attribution(
            @Schema(description = "The source of the text.", example = "Wikipedia") String source,
            @Schema(description = "The licence the text is under.", example = "CC BY-SA 4.0") String license,
            @Schema(description = "The licence itself.") String licenseUrl,
            @Schema(description = "A ready-made notice, in Italian, to show as it is.") String notice) {
    }

    private static final Attribution ATTRIBUTION = new Attribution(
            "Wikipedia",
            "CC BY-SA 4.0",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "Testi tratti da Wikipedia, nella lingua indicata da ogni sezione, disponibili con licenza "
                    + "CC BY-SA 4.0. Ogni voce rimanda all'articolo originale (url). Le immagini hanno "
                    + "licenze proprie, indicate nella pagina del file (filePageUrl).");

    public static OnThisDayResponse from(OnThisDayResult result) {
        Map<String, SectionResult> sections = new LinkedHashMap<>();
        for (Map.Entry<Section, SectionResult> entry : result.sections().entrySet()) {
            sections.put(entry.getKey().key(), entry.getValue());
        }
        return new OnThisDayResponse(
                new DateDto(result.day().getMonthValue(), result.day().getDayOfMonth()),
                result.requestedLanguage(), ATTRIBUTION, sections, result.warnings());
    }
}
