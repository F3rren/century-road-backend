package centuryroad.history.dto;

import centuryroad.history.model.Language;
import centuryroad.history.model.OnThisDayResult;
import centuryroad.history.model.Section;
import centuryroad.history.model.SectionResult;

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
public record OnThisDayResponse(DateDto date, Language language, Attribution attribution,
                                Map<String, SectionResult> sections, List<String> warnings) {

    public record DateDto(int month, int day) {
    }

    public record Attribution(String source, String license, String licenseUrl, String notice) {
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
