package centuryroad.history.controller;

import centuryroad.history.config.RequestCorrelationFilter;
import centuryroad.history.dto.ApiEnvelope;
import centuryroad.history.dto.ReportReceipt;
import centuryroad.history.dto.ReportRequest;
import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.service.ReportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * "Segnala un errore": the one public endpoint that writes to the database. It stores the
 * report and what it is about, and never who sent it (see V3 and ReportService).
 */
@RestController
@RequestMapping("/api/history/reports")
@Tag(name = "Reports", description = "\"Segnala un errore\": tell the editor something is wrong")
public class ReportsController {

    /**
     * A report is a few hundred bytes of JSON: the longest message is 1000 characters. Anything
     * past this is not one, and is not read: the body is read by hand, up to this many bytes,
     * because @RequestBody would read all of it - whatever its size, announced or chunked -
     * before a single check could run, on an endpoint anybody can call.
     */
    static final int MAX_BODY_BYTES = 8 * 1024;

    private final ReportService service;
    private final ObjectMapper mapper;

    public ReportsController(ReportService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Operation(summary = "Report a mistake in an event, an insight or a path",
            description = """
                    The frontend fills in `target` from the card the visitor is on, so the visitor only says
                    what is wrong. An EVENT is named by its date and edition (it has no identifier of its
                    own); an INSIGHT or a PATH by its slug. Nothing identifying the visitor is stored; the
                    email address is optional and only for replying.

                    Limited per visitor and for the whole service: a 429 carries `Retry-After`.""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReportRequest.class)))
    @ApiResponse(responseCode = "201", description = "The report was stored. Its number is in the answer.")
    @ApiResponse(responseCode = "400", description = "The report cannot be stored. `error` is INVALID_REPORT, "
            + "INVALID_DATE, INVALID_YEAR, UNSUPPORTED_LANGUAGE or BAD_REQUEST (no body, a body that is not "
            + "this shape, or one over 8 KB).", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @ApiResponse(responseCode = "429", description = "Too many reports. `error` is TOO_MANY_REQUESTS, with "
            + "`Retry-After`.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<ReportReceipt>> report(HttpServletRequest http) throws IOException {
        ReportRequest request = read(http);
        // The caller's own address once the proxy chain has been unwrapped: see
        // server.forward-headers-strategy in application-prod.yml.
        ReportReceipt receipt = service.submit(request, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiEnvelope.success(null, receipt, RequestCorrelationFilter.current()));
    }

    private ReportRequest read(HttpServletRequest http) throws IOException {
        byte[] body = http.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new InvalidRequestException("BAD_REQUEST", "report body is over " + MAX_BODY_BYTES + " bytes",
                    "La segnalazione è troppo lunga.");
        }
        try {
            return mapper.readValue(body, ReportRequest.class);
        } catch (JsonProcessingException e) {
            // What the parser says can quote the body back, so it stays out of the answer.
            throw new InvalidRequestException("BAD_REQUEST", "report body is not valid JSON of the expected shape",
                    "La richiesta non e' valida.");
        }
    }
}
