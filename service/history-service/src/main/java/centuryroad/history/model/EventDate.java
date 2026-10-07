package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.DateTimeException;
import java.time.MonthDay;
import java.util.Optional;

/**
 * A calendar date, year included. Month and day are always there, so dates sort and compare
 * without exceptions; {@code precision} says how much of them is real. Only a date known to the
 * day matches a day's events ("Approfondimento disponibile"). Why a date is not exact, or which
 * calendar it is in, is said in the insight's notes.
 */
@Schema(description = "A calendar date with its year, and how much of it is known.")
public record EventDate(
        @Schema(description = "The year. Negative before the common era.", example = "1957") int year,
        @Schema(description = "Month, 1 to 12. Always 1 when `precision` is `YEAR`: it then means nothing.",
                example = "10") int month,
        @Schema(description = "Day of the month. Always 1 when `precision` is `YEAR` or `MONTH`: it then means nothing.",
                example = "4") int day,
        @Schema(description = "How much of the date is known: only the `YEAR`, the `MONTH` too, or the whole `DAY`. "
                + "Show no more than that. `DAY` when the content does not say.", example = "DAY") DatePrecision precision) {

    public EventDate {
        precision = precision == null ? DatePrecision.DAY : precision;
    }

    /** Empty when month and day do not form a real day of the year (29 February is real). */
    @JsonIgnore
    public Optional<MonthDay> monthDay() {
        try {
            return Optional.of(MonthDay.of(month, day));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
