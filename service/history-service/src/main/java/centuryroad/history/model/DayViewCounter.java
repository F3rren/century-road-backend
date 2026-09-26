package centuryroad.history.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** How many times a calendar day's page has been viewed, in total. Written only through
 *  DayViewCounterRepository's atomic upsert - never via save(), so this entity carries no
 *  setters; the all-args constructor exists for tests to build a result row directly. */
@Entity
@Table(schema = "history", name = "day_views")
@IdClass(DayViewCounterId.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DayViewCounter {

    @Id
    private short month;

    @Id
    private short day;

    @Column(name = "view_count", nullable = false)
    private long viewCount;
}
