package centuryroad.history.repository;

import centuryroad.history.TestcontainersConfiguration;
import centuryroad.history.model.ErrorReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "Segnala un errore" against real Postgres: V3 applies, the entity validates against it, and the
 *  table's own checks refuse what the service would never send - the last line of defence. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ErrorReportRepositoryTest {

    private static final OffsetDateTime NOON = OffsetDateTime.of(2026, 9, 18, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private ErrorReportRepository repository;

    private static ErrorReport event(Integer year, Integer month, Integer day, String language) {
        return new ErrorReport(null, "EVENT", null, year, month == null ? null : month.shortValue(),
                day == null ? null : day.shortValue(), language, "Viene lanciato lo Sputnik 1.", "WRONG_DATE",
                "Una descrizione abbastanza lunga.", null, NOON, null);
    }

    private static ErrorReport insight(String slug) {
        return new ErrorReport(null, "INSIGHT", slug, null, null, null, null, null, "OTHER",
                "Una descrizione abbastanza lunga.", "nome@example.org", NOON, null);
    }

    @Test
    void anEventReportIsStoredAndReadBackWhole() {
        ErrorReport saved = repository.saveAndFlush(event(1957, 10, 4, "it"));

        ErrorReport read = repository.findById(saved.getId()).orElseThrow();
        assertThat(read.getTargetType()).isEqualTo("EVENT");
        assertThat(read.getTargetYear()).isEqualTo(1957);
        assertThat(read.getTargetMonth()).isEqualTo((short) 10);
        assertThat(read.getTargetText()).isEqualTo("Viene lanciato lo Sputnik 1.");
        assertThat(read.getCreatedAt()).isEqualTo(NOON);
        assertThat(read.getHandledAt()).isNull();
    }

    @Test
    void anInsightReportIsStoredByItsSlug_withItsContact() {
        ErrorReport saved = repository.saveAndFlush(insight("sputnik-1"));

        ErrorReport read = repository.findById(saved.getId()).orElseThrow();
        assertThat(read.getTargetSlug()).isEqualTo("sputnik-1");
        assertThat(read.getContact()).isEqualTo("nome@example.org");
        assertThat(read.getTargetYear()).isNull();
    }

    @Test
    void theTableRefusesAnEventWithoutItsDate_orAnInsightWithoutItsSlug() {
        assertThatThrownBy(() -> repository.saveAndFlush(event(null, 10, 4, "it")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTableRefusesAnInsightWithoutASlug() {
        assertThatThrownBy(() -> repository.saveAndFlush(insight(null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTableRefusesAnImpossibleMonth_aWrongLanguage_andAnUnknownCategory() {
        assertThatThrownBy(() -> repository.saveAndFlush(event(1957, 13, 4, "it")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTableRefusesAnUnknownLanguage() {
        assertThatThrownBy(() -> repository.saveAndFlush(event(1957, 10, 4, "fr")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTableRefusesAnUnknownCategory() {
        ErrorReport r = insight("sputnik-1");
        ErrorReport bad = new ErrorReport(null, r.getTargetType(), r.getTargetSlug(), null, null, null, null, null,
                "NOT_A_CATEGORY", r.getMessage(), null, NOON, null);

        assertThatThrownBy(() -> repository.saveAndFlush(bad)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
