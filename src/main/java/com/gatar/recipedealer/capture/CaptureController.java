package com.gatar.recipedealer.capture;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.processing.LlmExtractionService;
import com.gatar.recipedealer.recipe.ResolutionRepository.Action;
import com.gatar.recipedealer.recipe.ResolutionService;
import com.gatar.recipedealer.web.ApiException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/captures")
public class CaptureController {

    private final CaptureIngestService ingest;
    private final CaptureQueryService queries;
    private final ResolutionService resolutions;
    private final LlmExtractionService llm;
    private final CaptureDeletionService deletions;

    public CaptureController(CaptureIngestService ingest, CaptureQueryService queries, ResolutionService resolutions,
                             LlmExtractionService llm, CaptureDeletionService deletions) {
        this.ingest = ingest;
        this.queries = queries;
        this.resolutions = resolutions;
        this.llm = llm;
        this.deletions = deletions;
    }

    /** Multipart: part {@code capture} (JSON) plus one part per attachment, named like {@code attachments[].name}. */
    @PutMapping(path = "/{captureId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CaptureView> put(@PathVariable UUID captureId, HttpServletRequest request,
                                           @AuthenticationPrincipal DevicePrincipal device)
            throws IOException, ServletException {
        boolean created = ingest.ingest(captureId, request.getParts(), device);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(queries.view(captureId));
    }

    @GetMapping("/{captureId}")
    public CaptureView get(@PathVariable UUID captureId) {
        return queries.view(captureId);
    }

    /** The inbox; by default captures waiting for a decision or for attention. */
    @GetMapping
    public List<CaptureView> list(@RequestParam(defaultValue = "needs_decision,needs_attention") String status) {
        return queries.inbox(parseStatuses(status));
    }

    /** {@code tags}: tag ids for the new recipe (NEW only). */
    public record ResolutionRequest(Action action, UUID recipeId, List<String> tags) {
    }

    @PutMapping("/{captureId}/extracted/{index}/resolution")
    public CaptureView resolve(@PathVariable UUID captureId, @PathVariable int index,
                               @RequestBody ResolutionRequest request, @AuthenticationPrincipal DevicePrincipal device) {
        resolutions.request(captureId, index, request.action(), request.recipeId(), request.tags(), device);
        return queries.view(captureId);
    }

    /** Runs the extraction cascade again from the first LLM step; 202 with the capture (now {@code processing}). */
    @PostMapping("/{captureId}/llm-extraction")
    public ResponseEntity<CaptureView> forceLlmExtraction(@PathVariable UUID captureId) {
        llm.force(captureId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(queries.view(captureId));
    }

    /** Deletes a capture that never became a recipe, with its attachments; 409 once it did or while it is processed. */
    @DeleteMapping("/{captureId}")
    public ResponseEntity<Void> delete(@PathVariable UUID captureId) {
        deletions.delete(captureId);
        return ResponseEntity.noContent().build();
    }

    private static Set<CaptureStatus> parseStatuses(String value) {
        try {
            return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                    .map(CaptureStatus::of)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(CaptureStatus.class)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown status in '" + value + "'");
        }
    }
}
