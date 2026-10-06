package centuryroad.history.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the "Segnala un errore" button sends: what it is about, what kind of mistake, and a
 * few words. The target is filled in by the frontend from the card the visitor is on, so the
 * visitor never has to say which event - that is the point of the button.
 */
@Schema(description = "A report of a mistake, with the thing it is about already identified.")
public record ReportRequest(
        @Schema(description = "What the report is about.") Target target,
        @Schema(description = "What kind of mistake.") Category category,
        @Schema(description = "What is wrong, in the visitor's words: 10 to 1000 characters.", example = "La data è sbagliata: il lancio fu il 4 ottobre.") String message,
        @Schema(description = "An email address to answer at. Optional, and only used to reply about this report.", example = "nome@example.org") String contact) {

    @Schema(description = "What a report is about. An EVENT is named by its date and edition; an INSIGHT or a PATH by its slug.")
    public record Target(
            @Schema(description = "EVENT: one of Wikipedia's entries for a day. INSIGHT: a \"Perché conta\" block. PATH: a guided path.") Type type,
            @Schema(description = "For an INSIGHT or a PATH: its slug. Not for an EVENT.", example = "sputnik-1") String slug,
            @Schema(description = "For an EVENT: its year. Negative before the common era.", example = "1957") Integer year,
            @Schema(description = "For an EVENT: the month of the day page it is listed on, 1 to 12.", example = "10") Integer month,
            @Schema(description = "For an EVENT: the day of the month.", example = "4") Integer day,
            @Schema(description = "For an EVENT: the edition it was read in, it or en. Defaults to it.", example = "it") String language,
            @Schema(description = "For an EVENT: its text as the visitor saw it; anything past 500 characters is cut. It helps find the entry again, since an event has no identifier.") String text) {
    }

    @Schema(description = "What a report is about.")
    public enum Type {
        EVENT,
        INSIGHT,
        PATH
    }

    @Schema(description = "WRONG_DATE: the date is wrong or approximate. WRONG_PLACE: the place is wrong. WRONG_TEXT: the text is wrong or misleading. "
            + "BROKEN_LINK: a link or a source does not work. OTHER: anything else.")
    public enum Category {
        WRONG_DATE,
        WRONG_PLACE,
        WRONG_TEXT,
        BROKEN_LINK,
        OTHER
    }
}
