package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.DateTimeException;
import java.time.MonthDay;
import java.util.Optional;

/**
 * A full calendar date, year included. Editorial content always has one, because an
 * insight belongs to a day of the year: it is how "Approfondimento disponibile" is matched
 * to a day's events. Whether the date is exact is said in the insight's notes, not here.
 */
@Schema(description = "A calendar date with its year.")
public record EventDate(
        @Schema(description = "The year. Negative before the common era.", example = "1957") int year,
        @Schema(description = "Month, 1 to 12.", example = "10") int month,
        @Schema(description = "Day of the month.", example = "4") int day) {

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
