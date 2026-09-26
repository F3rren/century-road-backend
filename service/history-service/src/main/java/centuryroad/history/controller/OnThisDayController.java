package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.OnThisDayResponse;
import centuryroad.history.model.OnThisDayResult;
import centuryroad.history.query.OnThisDayQuery;
import centuryroad.history.service.OnThisDayService;
import centuryroad.history.service.ViewStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
 * What happened on a given day, from Wikipedia. Public: nothing here is per-user, and the
 * number of requests that can reach Wikipedia is bounded by the cache regardless of how
 * many people call this.
 *
 * The date is a path segment, month then day, the same order Wikipedia's own API uses.
 * Everything else is optional: lang (default it), types (comma-separated or repeated,
 * default all five), and a year filter, either year or fromYear/toYear. Year is applied
 * here because Wikipedia cannot filter by it.
 */
@RestController
@RequestMapping("/api/history")
@Tag(name = "History", description = "What happened on a calendar day, from Wikipedia's \"On this day\" feed")
public class OnThisDayController {

    private static final Duration CACHEABLE_FOR = Duration.ofMinutes(5);
    private static final Duration CACHEABLE_WHEN_DEGRADED = Duration.ofSeconds(30);

    private final OnThisDayService service;
    private final ViewStatsService viewStats;

    public OnThisDayController(OnThisDayService service, ViewStatsService viewStats) {
        this.service = service;
        this.viewStats = viewStats;
    }

    @Operation(
            summary = "What happened on a calendar day",
            description = """
                    Selected entries, events, births, deaths and holidays for one day of the year, from
                    Wikipedia. Each section says which language it really came from: the Italian feed has
                    no births or deaths, so those arrive in English with `fallback: true`.

                    In every item, `text` is the event and `pages` are the articles linked from it, in
                    the order they appear - related reading, not the article about the event.

                    The year filter narrows one day; Wikipedia cannot answer "everything in 1789".""")
    @ApiResponse(responseCode = "200", description = "The day, in the usual envelope. Cached for five minutes "
            + "(thirty seconds when the answer is degraded: a stale copy, or a language that could not be reached).")
    @ApiResponse(responseCode = "400", description = "The request cannot be answered and Wikipedia was not asked. "
            + "`error` is one of INVALID_DATE, UNSUPPORTED_LANGUAGE, INVALID_TYPE, INVALID_YEAR, BAD_REQUEST.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @ApiResponse(responseCode = "502", description = "Wikipedia answered with something unusable, for instance "
            + "because its API changed. `error` is UPSTREAM_BAD_RESPONSE.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @ApiResponse(responseCode = "503", description = "Wikipedia could not be used and there was no copy or other "
            + "language to fall back on. `error` is UPSTREAM_UNAVAILABLE, or UPSTREAM_RATE_LIMITED with a "
            + "`Retry-After` header saying for how long.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @GetMapping("/on-this-day/{month}/{day}")
    public ResponseEntity<ApiEnvelope<OnThisDayResponse>> onThisDay(
            @Parameter(description = "Month, 1 to 12.", example = "10",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "12"))
            @PathVariable int month,
            @Parameter(description = "Day of the month. It has to exist in that month: 2/29 is valid, 2/30 is a 400.",
                    example = "16", schema = @Schema(type = "integer", minimum = "1", maximum = "31"))
            @PathVariable int day,
            @Parameter(description = "Language of the text.",
                    schema = @Schema(allowableValues = {"it", "en"}, defaultValue = "it"))
            @RequestParam(defaultValue = "it") String lang,
            @Parameter(description = "Which sections to return, comma-separated or repeated. All five when omitted.",
                    array = @ArraySchema(schema = @Schema(
                            allowableValues = {"selected", "events", "births", "deaths", "holidays"})),
                    example = "events,births")
            @RequestParam(required = false) List<String> types,
            // No example on the three year filters, on purpose: "Try it out" sends every example, and
            // year cannot be combined with fromYear or toYear, so a first click would be a 400.
            @Parameter(description = "Only items from this year, such as `1969`. Negative before the common era "
                    + "(`-44`). Cannot be combined with fromYear or toYear; holidays have no year and are left out.")
            @RequestParam(required = false) Integer year,
            @Parameter(description = "Only items from this year on, inclusive, such as `1900`.")
            @RequestParam(required = false) Integer fromYear,
            @Parameter(description = "Only items up to this year, inclusive, such as `1999`.")
            @RequestParam(required = false) Integer toYear) {
        OnThisDayQuery query = OnThisDayQuery.of(month, day, lang, types, year, fromYear, toYear);
        OnThisDayResult result = service.find(query);
        // Every valid, answered request counts as a view of that day - degraded or not: the
        // visitor did land on it. A request for an invalid date never reaches this line.
        viewStats.recordDayView(query.day());

        // A degraded answer (stale copy, a language that could not be reached) is cached for
        // a moment only, so browsers and proxies come back for the good one soon.
        Duration cacheFor = result.degraded() ? CACHEABLE_WHEN_DEGRADED : CACHEABLE_FOR;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(cacheFor).cachePublic())
                .body(ApiEnvelope.success(null, OnThisDayResponse.from(result), RequestCorrelationFilter.current()));
    }
}
