package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.InsightDetail;
import centuryroad.history.dto.InsightSummary;
import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.exception.NotFoundException;
import centuryroad.history.model.Insight;
import centuryroad.history.query.OnThisDayQuery;
import centuryroad.history.service.EditorialCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * "Perché conta": the events that have been explained by hand, as opposed to merely listed.
 * Separate from the on-this-day endpoint on purpose: that one is a proxy of Wikipedia and
 * stays so, while these answer from the service's own content and keep working when Wikipedia
 * does not. A frontend marks a day's event "Approfondimento disponibile" by asking for the
 * insights of that day and matching them on the year.
 */
@RestController
@RequestMapping("/api/history/insights")
@Tag(name = "Insights", description = "\"Perché conta\": events explained in short pieces, drafts not yet reviewed - before, the event, after, where to read next")
public class InsightsController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final EditorialCatalog catalog;

    public InsightsController(EditorialCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "The events that have an insight, optionally for one day",
            description = """
                    Without parameters, every insight, oldest first. With `month` and `day`, only those of
                    that day of the year, whatever the year: the way to know which of a day's events from
                    /api/history/on-this-day to mark "Approfondimento disponibile". Match them on `date.year`.
                    A day with none is an empty list, not an error.""")
    @ApiResponse(responseCode = "200", description = "The insights, oldest first. Cached for five minutes.")
    @ApiResponse(responseCode = "400", description = "`error` is INVALID_DATE: only one of month and day was given, "
            + "or they are not a real day.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @GetMapping
    public ResponseEntity<ApiEnvelope<List<InsightSummary>>> insights(
            @Parameter(description = "Month, 1 to 12. Give it together with `day`.", schema = @Schema(type = "integer", minimum = "1", maximum = "12")) @RequestParam(required = false) Integer month,
            @Parameter(description = "Day of the month. Give it together with `month`.", schema = @Schema(type = "integer", minimum = "1", maximum = "31")) @RequestParam(required = false) Integer day) {
        if ((month == null) != (day == null)) {
            throw new InvalidRequestException("INVALID_DATE", "month and day go together",
                    "Indica sia il mese sia il giorno, oppure nessuno dei due.");
        }
        List<Insight> found = month == null ? catalog.insights()
                : catalog.insightsOn(OnThisDayQuery.parseDay(month, day));
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiEnvelope.success(null,
                found.stream().map(InsightSummary::from).toList(), RequestCorrelationFilter.current()));
    }

    @Operation(summary = "\"Perché conta\": one event explained",
            description = """
                    What led up to the event (`before`), what happened (`event`) and what followed
                    (`after`), plus two or three insights to read next (`related`), the paths it belongs
                    to, its sources, caveats about dates and places, and who wrote it. Written in Italian.""")
    @ApiResponse(responseCode = "200", description = "The insight. Cached for five minutes.")
    @ApiResponse(responseCode = "404", description = "No insight has this slug. `error` is INSIGHT_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @GetMapping("/{slug}")
    public ResponseEntity<ApiEnvelope<InsightDetail>> insight(
            @Parameter(description = "The insight's identifier, as listed by /api/history/insights.", example = "sputnik-1") @PathVariable String slug) {
        Insight insight = catalog.insight(slug).orElseThrow(() -> new NotFoundException("INSIGHT_NOT_FOUND",
                "No insight with slug " + slug, "Approfondimento non trovato."));
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiEnvelope.success(null,
                InsightDetail.from(insight, catalog), RequestCorrelationFilter.current()));
    }
}
