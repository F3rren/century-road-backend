package centuryroad.history.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** The composite key of DayViewCounter: a calendar day, month then day, the same order the
 *  on-this-day API's own path uses. */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class DayViewCounterId implements Serializable {
    private short month;
    private short day;
}
