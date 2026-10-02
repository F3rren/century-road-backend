package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.CountryEventCount;
import centuryroad.history.dto.TimelineResponse;
import centuryroad.history.model.Language;
import centuryroad.history.query.OnThisDayQuery;
import centuryroad.history.query.TimelineQuery;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Reads the country index TimelineIndexer builds overnight. Nothing here asks Wikipedia: an
 * index that is still being built answers with what it has, which is also why the answers
 * are cached for minutes rather than hours.
 */
@RestController
@RequestMapping("/api/history/countries")
@Tag(name = "Countries", description = "Each country's events across the whole year, from the nightly country index")
public class TimelineController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final TimelineEventRepository repository;

    public TimelineController(TimelineEventRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "The countries the index has events for, with how many",
            description = "Ordered by country code. Empty until the first pass of the index has run.")
    @ApiResponse(responseCode = "200", description = "The countries, with their event counts.")
    @ApiResponse(responseCode = "400", description = "`error` is UNSUPPORTED_LANGUAGE.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @GetMapping
    public ResponseEntity<ApiEnvelope<List<CountryEventCount>>> countries(
            @Parameter(description = "Language of the index.", schema = @Schema(allowableValues = { "it", "en" },
                    defaultValue = "it")) @RequestParam(defaultValue = "it") String lang) {
        Language language = OnThisDayQuery.parseLanguage(lang);
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiEnvelope.success(null,
                repository.countByCountry(language.code()), RequestCorrelationFilter.current()));
    }

    @Operation(summary = "One country's events across the whole year, oldest first",
            description = """
                    Every event with a year that Wikipedia lists on any day of the year and that the map's
                    own rule places in this country: the first linked article with coordinates, inside the
                    country's Natural Earth 1:110m shape. Rebuilt every night, so up to a day behind
                    Wikipedia. A valid code with nothing indexed is an empty list, not an error.""")
    @ApiResponse(responseCode = "200", description = "The events, oldest first.")
    @ApiResponse(responseCode = "400", description = "`error` is INVALID_COUNTRY_CODE, UNSUPPORTED_LANGUAGE, "
            + "INVALID_YEAR or BAD_REQUEST (a year that is not a number).",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @GetMapping("/{code}/timeline")
    public ResponseEntity<ApiEnvelope<TimelineResponse>> timeline(
            @Parameter(description = "ISO 3166-1 alpha-2 country code, uppercase.", example = "IT") @PathVariable String code,
            @Parameter(description = "Language of the texts.", schema = @Schema(allowableValues = { "it", "en" },
                    defaultValue = "it")) @RequestParam(defaultValue = "it") String lang,
            @Parameter(description = "Only events from this year on, inclusive. Negative before the common era.")
            @RequestParam(required = false) Integer fromYear,
            @Parameter(description = "Only events up to this year, inclusive.")
            @RequestParam(required = false) Integer toYear) {
        TimelineQuery query = TimelineQuery.of(code, lang, fromYear, toYear);
        var rows = repository.timeline(query.language().code(), query.countryCode(), query.years().from(),
                query.years().to());
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiEnvelope.success(null,
                TimelineResponse.from(query, rows), RequestCorrelationFilter.current()));
    }
}
