package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Who wrote an insight and whether anyone has checked it. reviewedAt is set only when a
 * real review happened - the day somebody went through the text against its sources - and
 * stays absent otherwise: a missing date is the truth, a made-up one would be a label of
 * reliability with nothing behind it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Who wrote an insight and when it was last really reviewed.")
public record Provenance(
        @Schema(description = "Who wrote the text.", example = "Century Road") String author,
        @Schema(description = "The day the text was last reviewed against its sources. Absent when it never was: "
                + "do not show a date the service does not have.", example = "2026-10-06") LocalDate reviewedAt) {
}
