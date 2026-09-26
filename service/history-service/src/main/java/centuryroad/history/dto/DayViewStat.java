package centuryroad.history.dto;

import centuryroad.history.model.DayViewCounter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many times a calendar day's page has been viewed, in total.")
public record DayViewStat(
        @Schema(description = "Month, 1 to 12.", example = "10") int month,
        @Schema(description = "Day of the month.", example = "16") int day,
        @Schema(description = "How many times this day has been viewed, in total.", example = "412") long viewCount) {

    public static DayViewStat from(DayViewCounter counter) {
        return new DayViewStat(counter.getMonth(), counter.getDay(), counter.getViewCount());
    }
}
