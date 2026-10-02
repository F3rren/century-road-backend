package centuryroad.history.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * One row of the country index: an event with a year, placed in a country by CountryLocator,
 * in the Wikipedia edition it came from. Derived data, written only by TimelineIndexer
 * through TimelineEventRepository.replaceDay - so no setters.
 */
@Entity
@Table(schema = "history", name = "timeline_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 2, nullable = false)
    private String language;

    @Column(nullable = false)
    private short month;

    @Column(nullable = false)
    private short day;

    @Column(nullable = false)
    private int year;

    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "indexed_at", nullable = false)
    private OffsetDateTime indexedAt;
}
