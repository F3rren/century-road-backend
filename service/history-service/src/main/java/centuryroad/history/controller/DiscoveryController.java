package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.RandomEventResponse;
import centuryroad.history.dto.SamePeriodResponse;
import centuryroad.history.query.RandomEventQuery;
import centuryroad.history.query.SamePeriodQuery;
import centuryroad.history.repository.TimelineEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Ways into the data that are not a search: a random event, and what else was happening in
 * the same years elsewhere. Both read the country index, so neither asks Wikipedia and both
 * are only as complete as the index is - which the answers say, rather than let a gap pass
 * for a quiet period.
 *
 * "Near this place" has no endpoint of its own: the map selects countries, and a country's
 * events across the year are /countries/{code}/timeline already.
 */
@RestController
@RequestMapping("/api/history")
@Tag(name = "Discovery", description = "Ways into the data that are not a search: a random event, and the same years elsewhere")
public class DiscoveryController {

    private static final CacheControl SAME_PERIOD_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final TimelineEventRepository repository;

    public DiscoveryController(TimelineEventRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "\"Sorprendimi\": a random event",
            description = """
                    One event picked at random from the country index, from those that match the filters
                    the visitor has active: a country, and a range of years. Every call can answer
                    differently, so it is never cached. The event carries its date and country: open the day
                    with /api/history/on-this-day/{month}/{day} for its articles and images.

                    Only events with a year that the map can place in a country are in the index, about
                    half of what Wikipedia lists. When nothing matches the filters the answer is a 200 with
                    no `event`.""")
    @ApiResponse(responseCode = "200", description = "The event, or none when nothing matches. Never cached.")
    @ApiResponse(responseCode = "400", description = "`error` is INVALID_COUNTRY_CODE, UNSUPPORTED_LANGUAGE, "
            + "INVALID_YEAR or BAD_REQUEST (a year that is not a number).",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @GetMapping("/random")
    public ResponseEntity<ApiEnvelope<RandomEventResponse>> random(
            @Parameter(description = "Language of the text.", schema = @Schema(allowableValues = { "it", "en" },
                    defaultValue = "it")) @RequestParam(defaultValue = "it") String lang,
            @Parameter(description = "Only events placed in this country. ISO 3166-1 alpha-2, uppercase.", example = "IT")
            @RequestParam(required = false) String country,
            @Parameter(description = "Only events from this year on, inclusive. Negative before the common era.")
            @RequestParam(required = false) Integer fromYear,
            @Parameter(description = "Only events up to this year, inclusive.")
            @RequestParam(required = false) Integer toYear) {
        RandomEventQuery query = RandomEventQuery.of(lang, country, fromYear, toYear);
        String language = query.language().code();
        var row = query.countryCode() == null
                ? repository.randomEvent(language, query.years().from(), query.years().to())
                : repository.randomEventIn(language, query.countryCode(), query.years().from(), query.years().to());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(null,
                RandomEventResponse.from(query.language(), row), RequestCorrelationFilter.current()));
    }

    @Operation(summary = "\"Nello stesso periodo\": events from the same years in other countries",
            description = """
                    What the country index has for the years around `year`, grouped by country, leaving out
                    `excludeCountry` - normally the one the visitor is looking at. A comparison in time and
                    nothing more: the events were contemporary, not connected, and the answer says so in
                    `notice`.

                    The index is a selection (what Wikipedia lists on its day pages and the map can place),
                    so `coverage` says how much there is for the window: NONE, SPARSE or OK. Show its note
                    and do not draw conclusions from a SPARSE window.""")
    @ApiResponse(responseCode = "200", description = "The countries with events in the window, most events first. "
            + "A window with nothing in it is a 200 with `coverage.level` NONE. Cached for five minutes.")
    @ApiResponse(responseCode = "400", description = "`error` is INVALID_YEAR, INVALID_SPAN, INVALID_LIMIT, "
            + "INVALID_COUNTRY_CODE, UNSUPPORTED_LANGUAGE or BAD_REQUEST (a value that is not a number, or no `year`).",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @GetMapping("/same-period")
    public ResponseEntity<ApiEnvelope<SamePeriodResponse>> samePeriod(
            @Parameter(description = "The year at the centre of the window. Negative before the common era.", example = "1969") @RequestParam int year,
            @Parameter(description = "Years either side of `year`, 0 to 25.", schema = @Schema(type = "integer",
                    minimum = "0", maximum = "25", defaultValue = "5")) @RequestParam(required = false) Integer span,
            @Parameter(description = "Language of the texts.", schema = @Schema(allowableValues = { "it", "en" },
                    defaultValue = "it")) @RequestParam(defaultValue = "it") String lang,
            @Parameter(description = "Leave out this country, usually the one being looked at. ISO 3166-1 alpha-2, uppercase.", example = "IT")
            @RequestParam(required = false) String excludeCountry,
            @Parameter(description = "How many events of each country to show, 1 to 10.", schema = @Schema(type = "integer",
                    minimum = "1", maximum = "10", defaultValue = "3")) @RequestParam(required = false) Integer perCountry) {
        SamePeriodQuery query = SamePeriodQuery.of(year, span, lang, excludeCountry, perCountry);
        var rows = repository.inYears(query.language().code(), query.fromYear(), query.toYear());
        return ResponseEntity.ok().cacheControl(SAME_PERIOD_CACHE).body(ApiEnvelope.success(null,
                SamePeriodResponse.from(query, rows), RequestCorrelationFilter.current()));
    }
}
