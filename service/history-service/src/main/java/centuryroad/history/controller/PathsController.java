package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.InsightSummary;
import centuryroad.history.dto.PathDetail;
import centuryroad.history.dto.PathSummary;
import centuryroad.history.dto.StartHereItem;
import centuryroad.history.exception.NotFoundException;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.StartHerePick;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * The answer to "I have just arrived: where do I begin?": a handful of hand-picked proposals
 * and the guided paths behind them. All of it is read from EditorialCatalog, in memory - no
 * database, and no Wikipedia, so these endpoints answer even when Wikipedia does not. The
 * content only changes with a release, hence the long cache.
 */
@RestController
@RequestMapping("/api/history")
@Tag(name = "Paths", description = "Guided paths and the \"Inizia da qui\" proposals: small narrative itineraries written by hand")
public class PathsController {

    // Changes only when a new version is deployed; an hour is long enough to matter and short
    // enough that a release is seen the same day.
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final EditorialCatalog catalog;

    public PathsController(EditorialCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "\"Inizia da qui\": a few proposals picked by hand",
            description = """
                    For a visitor who does not know where to begin: a short list of paths and events
                    chosen by the editor, each with a sentence on why to open it. Open a PATH at
                    /api/history/paths/{slug} and an INSIGHT at /api/history/insights/{slug}.""")
    @ApiResponse(responseCode = "200", description = "The proposals, in the order to show them. Cached for an hour.")
    @GetMapping("/start-here")
    public ResponseEntity<ApiEnvelope<List<StartHereItem>>> startHere() {
        List<StartHereItem> items = catalog.startHere().stream().map(this::item).toList();
        return ResponseEntity.ok().cacheControl(CACHE)
                .body(ApiEnvelope.success(null, items, RequestCorrelationFilter.current()));
    }

    private StartHereItem item(StartHerePick pick) {
        if (pick.type() == StartHerePick.Type.PATH) {
            GuidedPath path = catalog.path(pick.slug()).orElseThrow();
            return StartHereItem.ofPath(pick, PathSummary.from(path, catalog.readingMinutes(path)));
        }
        return StartHereItem.ofInsight(pick, InsightSummary.from(catalog.insight(pick.slug()).orElseThrow()));
    }

    @Operation(summary = "The guided paths",
            description = "Every path as a card: title, one sentence, cover, reading time and number of stops. "
                    + "Ordered by slug.")
    @ApiResponse(responseCode = "200", description = "The paths. Cached for an hour.")
    @GetMapping("/paths")
    public ResponseEntity<ApiEnvelope<List<PathSummary>>> paths() {
        List<PathSummary> paths = catalog.paths().stream()
                .map(path -> PathSummary.from(path, catalog.readingMinutes(path)))
                .toList();
        return ResponseEntity.ok().cacheControl(CACHE)
                .body(ApiEnvelope.success(null, paths, RequestCorrelationFilter.current()));
    }

    @Operation(summary = "One guided path, with its stops in order",
            description = """
                    The introduction and every stop: its place (for the map to move to), its date and the
                    line that ties it to the path. A stop's full explanation is the insight with the stop's
                    slug, asked for when the stop is opened.""")
    @ApiResponse(responseCode = "200", description = "The path. Cached for an hour.")
    @ApiResponse(responseCode = "404", description = "No path has this slug. `error` is PATH_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @GetMapping("/paths/{slug}")
    public ResponseEntity<ApiEnvelope<PathDetail>> path(
            @Parameter(description = "The path's identifier, as listed by /api/history/paths.", example = "conquista-dello-spazio") @PathVariable String slug) {
        GuidedPath path = catalog.path(slug).orElseThrow(() -> new NotFoundException("PATH_NOT_FOUND",
                "No path with slug " + slug, "Percorso non trovato."));
        return ResponseEntity.ok().cacheControl(CACHE)
                .body(ApiEnvelope.success(null, PathDetail.from(path, catalog), RequestCorrelationFilter.current()));
    }
}
