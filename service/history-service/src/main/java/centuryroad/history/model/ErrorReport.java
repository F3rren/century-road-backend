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
 * One row of "Segnala un errore", as ReportService writes it. Written once and never edited by
 * the service - handling a report is done in SQL - so no setters. The enums are kept as plain
 * strings, validated before they get here and by the table's own checks after it.
 */
@Entity
@Table(schema = "history", name = "error_reports")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ErrorReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", length = 10, nullable = false)
    private String targetType;

    @Column(name = "target_slug", length = 100)
    private String targetSlug;

    @Column(name = "target_year")
    private Integer targetYear;

    @Column(name = "target_month")
    private Short targetMonth;

    @Column(name = "target_day")
    private Short targetDay;

    @Column(name = "target_language", length = 2)
    private String targetLanguage;

    @Column(name = "target_text", length = 500)
    private String targetText;

    @Column(length = 20, nullable = false)
    private String category;

    @Column(length = 1000, nullable = false)
    private String message;

    @Column(length = 254)
    private String contact;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "handled_at")
    private OffsetDateTime handledAt;
}
