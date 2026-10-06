package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.SourcesResponse;
import centuryroad.history.repository.TimelineEventRepository;
import centuryroad.history.service.EditorialCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** The data behind "Il progetto e le fonti": provenance, terms, coverage and limits. */
@RestController
@RequestMapping("/api/history/sources")
@Tag(name = "Sources", description = "Where the content comes from, under what terms, and what it leaves out")
public class SourcesController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final TimelineEventRepository repository;
    private final EditorialCatalog catalog;

    public SourcesController(TimelineEventRepository repository, EditorialCatalog catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    @Operation(summary = "The sources, their terms, the coverage and the limits",
            description = """
                    For the project and sources page: every source of content with its licence and whether it
                    must be credited, how much content there is (the country index per edition, and how many
                    paths and insights, of which how many were reviewed), and what the content is not.

                    It is provenance, not a seal of reliability: nothing in it says "verified". An insight
                    with a review date was checked by a person; every other text is Wikipedia's, shown with
                    where it comes from.""")
    @ApiResponse(responseCode = "200", description = "The sources. Cached for five minutes.")
    @GetMapping
    public ResponseEntity<ApiEnvelope<SourcesResponse>> sources() {
        return ResponseEntity.ok().cacheControl(CACHE).body(ApiEnvelope.success(null,
                SourcesResponse.from(repository.coverage(), catalog.paths(), catalog.insights()),
                RequestCorrelationFilter.current()));
    }
}
