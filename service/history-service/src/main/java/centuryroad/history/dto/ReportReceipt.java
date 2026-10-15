package centuryroad.history.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Proof that a report was received.")
public record ReportReceipt(
        @Schema(description = "The report's number. Quote it if the visitor writes back about it.", example = "42") long id,
        @Schema(description = "When it was received.") OffsetDateTime receivedAt) {
}
