package centuryroad.history.service;

import centuryroad.history.dto.ReportReceipt;
import centuryroad.history.dto.ReportRequest;
import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.model.ErrorReport;
import centuryroad.history.model.Language;
import centuryroad.history.query.OnThisDayQuery;
import centuryroad.history.repository.ErrorReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/**
 * "Segnala un errore". Checks what the visitor sent, applies the rate limit, writes one row.
 *
 * The order is deliberate: a malformed report is refused first, and does not count against
 * the visitor's limit - a typo should cost nothing, and nothing malformed ever reaches the
 * database anyway. Only a report that would be stored uses up a place.
 *
 * What is logged is that a report arrived and what it is about, never what it says or who
 * sent it: the message is free text from a stranger, and the contact address is personal.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    static final int MIN_MESSAGE = 10;
    static final int MAX_MESSAGE = 1000;
    static final int MAX_CONTACT = 254;
    static final int MAX_TEXT = 500;

    // Not RFC 5322, deliberately: something@something.tld, no spaces. The address is only ever
    // written to; a typo there means no reply, which the visitor chose by leaving one.
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ErrorReportRepository repository;
    private final EditorialCatalog catalog;
    private final ReportRateLimiter limiter;
    private final Clock clock;

    public ReportService(ErrorReportRepository repository, EditorialCatalog catalog, ReportRateLimiter limiter,
            Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.limiter = limiter;
        this.clock = clock;
    }

    public ReportReceipt submit(ReportRequest request, String client) {
        ErrorReport report = toRow(request);
        limiter.acquire(client);
        ErrorReport saved = repository.save(report);
        log.info("Error report {} received about {} {}", saved.getId(), saved.getTargetType(),
                saved.getTargetSlug() != null ? saved.getTargetSlug()
                        : saved.getTargetYear() + "-" + saved.getTargetMonth() + "-" + saved.getTargetDay());
        return new ReportReceipt(saved.getId(), saved.getCreatedAt());
    }

    private ErrorReport toRow(ReportRequest request) {
        if (request == null || request.target() == null || request.target().type() == null
                || request.category() == null) {
            throw invalid("target.type and category are required", "Indica che cosa stai segnalando e di che tipo di errore si tratta.");
        }
        String message = clean(request.message());
        if (message == null || message.length() < MIN_MESSAGE || message.length() > MAX_MESSAGE) {
            throw invalid("message must be " + MIN_MESSAGE + " to " + MAX_MESSAGE + " characters",
                    "Descrivi l'errore in modo che si capisca: da " + MIN_MESSAGE + " a " + MAX_MESSAGE + " caratteri.");
        }
        String contact = clean(request.contact());
        if (contact != null && (contact.length() > MAX_CONTACT || !EMAIL.matcher(contact).matches())) {
            throw invalid("contact is not an email address", "L'indirizzo email non sembra valido: puoi anche lasciarlo vuoto.");
        }

        ReportRequest.Target target = request.target();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String type = target.type().name();
        String category = request.category().name();

        if (target.type() == ReportRequest.Type.EVENT) {
            if (target.year() == null || target.month() == null || target.day() == null) {
                throw invalid("an EVENT needs year, month and day",
                        "Per segnalare un evento servono anno, mese e giorno.");
            }
            if (target.year() < OnThisDayQuery.MIN_YEAR || target.year() > OnThisDayQuery.MAX_YEAR) {
                throw new InvalidRequestException("INVALID_YEAR", "year out of range",
                        "L'anno deve essere compreso tra " + OnThisDayQuery.MIN_YEAR + " e " + OnThisDayQuery.MAX_YEAR + ".");
            }
            var day = OnThisDayQuery.parseDay(target.month(), target.day());
            Language language = OnThisDayQuery.parseLanguage(target.language() == null ? "it" : target.language());
            String text = clean(target.text());
            if (text != null && text.length() > MAX_TEXT) {
                text = text.substring(0, MAX_TEXT);
            }
            return new ErrorReport(null, type, null, target.year(), (short) day.getMonthValue(),
                    (short) day.getDayOfMonth(), language.code(), text, category, message, contact, now, null);
        }

        String slug = clean(target.slug());
        boolean exists = slug != null && (target.type() == ReportRequest.Type.INSIGHT
                ? catalog.insight(slug).isPresent() : catalog.path(slug).isPresent());
        if (!exists) {
            throw invalid("no " + type + " with slug " + slug, "Non trovo l'elemento che vuoi segnalare.");
        }
        return new ErrorReport(null, type, slug, null, null, null, null, null, category, message, contact, now, null);
    }

    /** Trimmed; null when nothing is left. */
    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static InvalidRequestException invalid(String message, String userMessage) {
        return new InvalidRequestException("INVALID_REPORT", message, userMessage);
    }
}
