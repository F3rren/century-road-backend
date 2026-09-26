package centuryroad.history.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** How many times a country has been viewed, in total, keyed by its ISO 3166-1 alpha-2 code.
 *  Written only through CountryViewCounterRepository's atomic upsert - never via save(), so
 *  this entity carries no setters; the all-args constructor exists for tests to build a
 *  result row directly. */
@Entity
@Table(schema = "history", name = "country_views")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CountryViewCounter {

    @Id
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "view_count", nullable = false)
    private long viewCount;
}
