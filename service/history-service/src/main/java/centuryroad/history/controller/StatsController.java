package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.CountryViewStat;
import centuryroad.history.dto.DayViewStat;
import centuryroad.history.service.ViewStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reads back what TrackingController and OnThisDayController's own counting have recorded -
 *  the most-viewed days and countries, most viewed first. Public, like the rest of this
 *  service: the numbers are aggregate counts, not attributable to anyone. */
@RestController
@RequestMapping("/api/history/stats")
@Tag(name = "Stats", description = "Aggregate, anonymous view counters, most viewed first")
public class StatsController {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ViewStatsService viewStats;

    public StatsController(ViewStatsService viewStats) {
        this.viewStats = viewStats;
    }

    private static int bounded(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    @Operation(summary = "The most-viewed calendar days, most viewed first")
    @GetMapping("/days")
    public ResponseEntity<ApiEnvelope<List<DayViewStat>>> topDays(
            @Parameter(description = "How many to return, 1 to " + MAX_LIMIT + ".")
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT)
            @Schema(minimum = "1", maximum = "" + MAX_LIMIT) int limit) {
        List<DayViewStat> stats = viewStats.topDays(bounded(limit)).stream().map(DayViewStat::from).toList();
        return ResponseEntity.ok(ApiEnvelope.success(null, stats, RequestCorrelationFilter.current()));
    }

    @Operation(summary = "The most-viewed countries, most viewed first")
    @GetMapping("/countries")
    public ResponseEntity<ApiEnvelope<List<CountryViewStat>>> topCountries(
            @Parameter(description = "How many to return, 1 to " + MAX_LIMIT + ".")
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT)
            @Schema(minimum = "1", maximum = "" + MAX_LIMIT) int limit) {
        List<CountryViewStat> stats = viewStats.topCountries(bounded(limit)).stream().map(CountryViewStat::from).toList();
        return ResponseEntity.ok(ApiEnvelope.success(null, stats, RequestCorrelationFilter.current()));
    }
}
