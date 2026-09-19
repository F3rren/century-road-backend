package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.OnThisDayResponse;
import centuryroad.history.model.OnThisDayResult;
import centuryroad.history.query.OnThisDayQuery;
import centuryroad.history.service.OnThisDayService;
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
public class OnThisDayController {

    private static final Duration CACHEABLE_FOR = Duration.ofMinutes(5);
    private static final Duration CACHEABLE_WHEN_DEGRADED = Duration.ofSeconds(30);

    private final OnThisDayService service;

    public OnThisDayController(OnThisDayService service) {
        this.service = service;
    }

    @GetMapping("/on-this-day/{month}/{day}")
    public ResponseEntity<ApiEnvelope<OnThisDayResponse>> onThisDay(
            @PathVariable int month,
            @PathVariable int day,
            @RequestParam(defaultValue = "it") String lang,
            @RequestParam(required = false) List<String> types,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer fromYear,
            @RequestParam(required = false) Integer toYear) {
        OnThisDayQuery query = OnThisDayQuery.of(month, day, lang, types, year, fromYear, toYear);
        OnThisDayResult result = service.find(query);

        // A degraded answer (stale copy, a language that could not be reached) is cached for
        // a moment only, so browsers and proxies come back for the good one soon.
        Duration cacheFor = result.degraded() ? CACHEABLE_WHEN_DEGRADED : CACHEABLE_FOR;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(cacheFor).cachePublic())
                .body(ApiEnvelope.success(null, OnThisDayResponse.from(result), RequestCorrelationFilter.current()));
    }
}
