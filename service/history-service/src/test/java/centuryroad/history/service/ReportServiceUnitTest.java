package centuryroad.history.service;

import centuryroad.history.TestClock;
import centuryroad.history.TestSupport;
import centuryroad.history.dto.ReportReceipt;
import centuryroad.history.dto.ReportRequest;
import centuryroad.history.dto.ReportRequest.Category;
import centuryroad.history.dto.ReportRequest.Target;
import centuryroad.history.dto.ReportRequest.Type;
import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.exception.TooManyRequestsException;
import centuryroad.history.model.ErrorReport;
import centuryroad.history.repository.ErrorReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What a report must be to be stored, and what is stored of it: validated first, rate-limited
 * after, and nothing about the visitor but the address they chose to leave.
 */
class ReportServiceUnitTest {

    private static final String MESSAGE = "La data è sbagliata: il lancio fu il 4 ottobre.";

    private final TestClock clock = TestClock.atNoon();
    private final ErrorReportRepository repository = mock(ErrorReportRepository.class);
    private final EditorialCatalog catalog = new EditorialCatalog(new PathMatchingResourcePatternResolver(),
            "classpath:editorial-test");
    private final ReportRateLimiter limiter = new ReportRateLimiter(TestSupport.properties(), clock);
    private final ReportService service = new ReportService(repository, catalog, limiter, clock);

    @BeforeEach
    void saving() {
        given(repository.save(any(ErrorReport.class))).willAnswer(call -> {
            ErrorReport r = call.getArgument(0);
            return new ErrorReport(42L, r.getTargetType(), r.getTargetSlug(), r.getTargetYear(), r.getTargetMonth(),
                    r.getTargetDay(), r.getTargetLanguage(), r.getTargetText(), r.getCategory(), r.getMessage(),
                    r.getContact(), r.getCreatedAt(), null);
        });
    }

    private static ReportRequest event(Integer year, Integer month, Integer day, String language, String text) {
        return new ReportRequest(new Target(Type.EVENT, null, year, month, day, language, text),
                Category.WRONG_DATE, MESSAGE, null);
    }

    private static ReportRequest insight(String slug) {
        return new ReportRequest(new Target(Type.INSIGHT, slug, null, null, null, null, null),
                Category.WRONG_TEXT, MESSAGE, null);
    }

    private ErrorReport stored() {
        ArgumentCaptor<ErrorReport> captor = ArgumentCaptor.forClass(ErrorReport.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private void assertRefused(ReportRequest request, String code) {
        assertThatThrownBy(() -> service.submit(request, "1.1.1.1"))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
        verify(repository, never()).save(any());
    }

    // ---- what is stored ------------------------------------------------------------------------

    @Test
    void anEventIsStoredByItsDateEditionAndText_andTheReceiptCarriesItsNumber() {
        ReportReceipt receipt = service.submit(event(1957, 10, 4, "en", "  Sputnik 1 is launched.  "), "1.1.1.1");

        ErrorReport row = stored();
        assertThat(row.getTargetType()).isEqualTo("EVENT");
        assertThat(row.getTargetSlug()).isNull();
        assertThat(row.getTargetYear()).isEqualTo(1957);
        assertThat(row.getTargetMonth()).isEqualTo((short) 10);
        assertThat(row.getTargetDay()).isEqualTo((short) 4);
        assertThat(row.getTargetLanguage()).isEqualTo("en");
        assertThat(row.getTargetText()).isEqualTo("Sputnik 1 is launched.");
        assertThat(row.getCategory()).isEqualTo("WRONG_DATE");
        assertThat(row.getMessage()).isEqualTo(MESSAGE);
        assertThat(row.getCreatedAt()).isEqualTo(OffsetDateTime.of(2026, 9, 18, 12, 0, 0, 0, ZoneOffset.UTC));
        assertThat(receipt.id()).isEqualTo(42L);
        assertThat(receipt.receivedAt()).isEqualTo(row.getCreatedAt());
    }

    @Test
    void anEventWithoutALanguageIsItalian_andALongTextIsCutNotRefused() {
        service.submit(event(1957, 10, 4, null, "x".repeat(900)), "1.1.1.1");

        ErrorReport row = stored();
        assertThat(row.getTargetLanguage()).isEqualTo("it");
        assertThat(row.getTargetText()).hasSize(500);
    }

    @Test
    void anInsightIsStoredByItsSlugAlone_whateverElseWasSent() {
        ReportRequest request = new ReportRequest(
                new Target(Type.INSIGHT, "test-a", 1999, 1, 1, "en", "stray text"), Category.OTHER, MESSAGE, null);

        service.submit(request, "1.1.1.1");

        ErrorReport row = stored();
        assertThat(row.getTargetType()).isEqualTo("INSIGHT");
        assertThat(row.getTargetSlug()).isEqualTo("test-a");
        assertThat(row.getTargetYear()).isNull();
        assertThat(row.getTargetText()).isNull();
        assertThat(row.getTargetLanguage()).isNull();
    }

    @Test
    void aPathIsStoredByItsSlug() {
        service.submit(new ReportRequest(new Target(Type.PATH, "test-path", null, null, null, null, null),
                Category.BROKEN_LINK, MESSAGE, null), "1.1.1.1");

        assertThat(stored().getTargetType()).isEqualTo("PATH");
        assertThat(stored().getTargetSlug()).isEqualTo("test-path");
    }

    @Test
    void theContactIsKeptWhenGivenAndAbsentWhenBlank() {
        service.submit(new ReportRequest(insight("test-a").target(), Category.OTHER, MESSAGE, " nome@example.org "),
                "1.1.1.1");
        assertThat(stored().getContact()).isEqualTo("nome@example.org");
    }

    @Test
    void aBlankContactIsNoContact() {
        service.submit(new ReportRequest(insight("test-a").target(), Category.OTHER, MESSAGE, "   "), "1.1.1.1");

        assertThat(stored().getContact()).isNull();
    }

    // ---- what is refused -----------------------------------------------------------------------

    @Test
    void aReportWithoutATargetOrACategoryIsRefused() {
        assertRefused(new ReportRequest(null, Category.OTHER, MESSAGE, null), "INVALID_REPORT");
        assertRefused(new ReportRequest(new Target(null, "test-a", null, null, null, null, null), Category.OTHER,
                MESSAGE, null), "INVALID_REPORT");
        assertRefused(new ReportRequest(insight("test-a").target(), null, MESSAGE, null), "INVALID_REPORT");
        assertRefused(null, "INVALID_REPORT");
    }

    @Test
    void aMessageThatIsTooShortOrTooLongOrBlankIsRefused() {
        for (String message : new String[]{null, "", "   ", "troppo corto", "x".repeat(1001)}) {
            if ("troppo corto".equals(message)) {
                message = "corto"; // 5 characters
            }
            assertRefused(new ReportRequest(insight("test-a").target(), Category.OTHER, message, null),
                    "INVALID_REPORT");
        }
    }

    @Test
    void aMessageAtTheLimitsIsAccepted() {
        service.submit(new ReportRequest(insight("test-a").target(), Category.OTHER, "x".repeat(10), null), "a");
        service.submit(new ReportRequest(insight("test-a").target(), Category.OTHER, "x".repeat(1000), null), "b");
    }

    @Test
    void aContactThatIsNotAnEmailAddressIsRefused() {
        for (String contact : new String[]{"nome", "nome@", "@example.org", "nome@example", "no me@example.org"}) {
            assertRefused(new ReportRequest(insight("test-a").target(), Category.OTHER, MESSAGE, contact),
                    "INVALID_REPORT");
        }
    }

    @Test
    void anEventNeedsItsFullDate_realOnesOnly() {
        assertRefused(event(null, 10, 4, "it", null), "INVALID_REPORT");
        assertRefused(event(1957, null, 4, "it", null), "INVALID_REPORT");
        assertRefused(event(1957, 10, null, "it", null), "INVALID_REPORT");
        assertRefused(event(1957, 2, 30, "it", null), "INVALID_DATE");
        assertRefused(event(1957, 13, 1, "it", null), "INVALID_DATE");
        assertRefused(event(10000, 1, 1, "it", null), "INVALID_YEAR");
        assertRefused(event(1957, 10, 4, "fr", null), "UNSUPPORTED_LANGUAGE");
    }

    @Test
    void aReportAboutSomethingThatDoesNotExistIsRefused() {
        assertRefused(insight("ghost"), "INVALID_REPORT");
        assertRefused(insight(null), "INVALID_REPORT");
        assertRefused(new ReportRequest(new Target(Type.PATH, "test-a", null, null, null, null, null),
                Category.OTHER, MESSAGE, null), "INVALID_REPORT"); // test-a is an insight, not a path
    }

    // ---- limiting ------------------------------------------------------------------------------

    @Test
    void aRefusedReportDoesNotUseUpAPlaceInTheLimit() {
        for (int i = 0; i < 20; i++) {
            try {
                service.submit(insight("ghost"), "1.1.1.1");
            } catch (InvalidRequestException expected) {
                // Twenty malformed reports, none counted.
            }
        }

        for (int i = 0; i < 5; i++) {
            service.submit(insight("test-a"), "1.1.1.1");
        }
    }

    @Test
    void theSixthReportOfTheHourFromOneVisitorIsRefused_andNothingIsStored() {
        for (int i = 0; i < 5; i++) {
            service.submit(insight("test-a"), "1.1.1.1");
        }

        assertThatThrownBy(() -> service.submit(insight("test-a"), "1.1.1.1"))
                .isInstanceOf(TooManyRequestsException.class);
        verify(repository, org.mockito.Mockito.times(5)).save(any());
    }

    @Test
    void nothingIsAskedOfTheDatabaseByAnInvalidReport() {
        assertRefused(insight("ghost"), "INVALID_REPORT");

        verifyNoInteractions(repository);
    }
}
