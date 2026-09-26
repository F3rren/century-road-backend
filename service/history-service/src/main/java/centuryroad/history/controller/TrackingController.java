package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.service.ViewStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * Anonymous, aggregate view tracking. There is exactly one signal here - "a country was
 * viewed" - with no visitor identifier attached to it; see the migration and
 * ViewStatsService. Public and unauthenticated, the same as on-this-day itself: nothing
 * here is per-user.
 *
 * Day views are counted directly in OnThisDayController, on every real request - there is
 * no separate endpoint for those, since the day is already known from the request being
 * made. Country is different: the backend never learns which country a request is "about",
 * that guess happens client-side (geocoding events against map shapes), so the frontend
 * reports it explicitly once a visitor selects one.
 */
@RestController
@RequestMapping("/api/history/track")
@Tag(name = "Tracking", description = "Anonymous, aggregate view counters")
public class TrackingController {

    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    private final ViewStatsService viewStats;

    public TrackingController(ViewStatsService viewStats) {
        this.viewStats = viewStats;
    }

    @Operation(
            summary = "Record an anonymous view of a country",
            description = "Increments that country's aggregate view counter by one. The write happens off the "
                    + "request thread and never fails the request, so this always answers as soon as the code "
                    + "is validated - the increment itself may still be in flight when it does.")
    @ApiResponse(responseCode = "202", description = "Accepted.")
    @ApiResponse(responseCode = "400", description = "code is not two uppercase ISO 3166-1 alpha-2 letters. "
            + "`error` is INVALID_COUNTRY_CODE.")
    @PostMapping("/country/{code}")
    public ResponseEntity<ApiEnvelope<Void>> trackCountry(
            @Parameter(description = "ISO 3166-1 alpha-2 country code, uppercase.", example = "IT")
            @PathVariable String code) {
        if (!COUNTRY_CODE.matcher(code).matches()) {
            throw new InvalidRequestException("INVALID_COUNTRY_CODE",
                    "code must be two uppercase ISO 3166-1 alpha-2 letters, got: " + code,
                    "Codice paese non valido.");
        }
        viewStats.recordCountryView(code);
        return ResponseEntity.accepted().body(ApiEnvelope.success(null, null, RequestCorrelationFilter.current()));
    }
}
