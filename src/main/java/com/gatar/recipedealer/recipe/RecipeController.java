package com.gatar.recipedealer.recipe;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.processing.ExtractionStatsService;
import com.gatar.recipedealer.web.ETags;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/recipes")
public class RecipeController {

    public static final String LOCK_ID = "Lock-Id";

    private final CandidateService candidates;
    private final RecipeQueryService queries;
    private final RecipeEditService edits;
    private final ExtractionStatsService stats;

    public RecipeController(CandidateService candidates, RecipeQueryService queries, RecipeEditService edits,
                            ExtractionStatsService stats) {
        this.candidates = candidates;
        this.queries = queries;
        this.edits = edits;
        this.stats = stats;
    }

    /** Extraction runs of the recipe's captures with token usage and cost ("Statystyki dla nerdów"). */
    @GetMapping("/{recipeId}/extraction-stats")
    public ExtractionStatsService.RecipeStats extractionStats(@PathVariable UUID recipeId) {
        return stats.forRecipe(recipeId);
    }

    @GetMapping("/candidates")
    public List<CandidateService.Candidate> candidates(@RequestParam(required = false) String title,
                                                       @RequestParam(required = false) String url) {
        return candidates.find(title, url);
    }

    /** Sync feed for the offline cache. */
    @GetMapping
    public RecipeQueryService.SyncPage sync(@RequestParam(required = false) Instant updatedSince,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return queries.sync(updatedSince, cursor, limit);
    }

    @GetMapping("/search")
    public List<RecipeQueryService.SearchHit> search(@RequestParam String q) {
        return queries.search(q);
    }

    /** The head revision; {@code ETag} is the head revision id (used as {@code If-Match} when saving an edit). */
    @GetMapping("/{recipeId}")
    public ResponseEntity<RecipeQueryService.RecipeView> recipe(@PathVariable UUID recipeId,
                                                                @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        var view = queries.recipe(recipeId);
        String etag = ETags.of(view.headRevisionId());
        if (ETags.matches(ifNoneMatch, view.headRevisionId())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(view);
    }

    @GetMapping("/{recipeId}/revisions")
    public List<RecipeQueryService.RevisionView> revisions(@PathVariable UUID recipeId) {
        return queries.revisions(recipeId);
    }

    /**
     * Saves an edit: {@code Lock-Id} of the active lease and {@code If-Match} with the head the editor started from.
     * 201 with the new head (lock released) · 200 if the content equals the head · 409 lock · 412 head changed.
     */
    @PostMapping("/{recipeId}/revisions")
    public ResponseEntity<RecipeQueryService.RecipeView> save(@PathVariable UUID recipeId,
                                                              @RequestHeader(value = LOCK_ID, required = false) UUID lockId,
                                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                              @RequestBody JsonNode recipe,
                                                              @AuthenticationPrincipal DevicePrincipal device) {
        var outcome = edits.save(recipeId, lockId, ifMatch, recipe, device);
        var view = queries.recipe(recipeId);
        var response = outcome.created()
                ? ResponseEntity.created(URI.create("/v1/recipes/" + recipeId))
                : ResponseEntity.ok();
        return response.eTag(ETags.of(view.headRevisionId())).body(view);
    }

    /** Soft delete; needs the edit lock. */
    @DeleteMapping("/{recipeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID recipeId,
                       @RequestHeader(value = LOCK_ID, required = false) UUID lockId,
                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        edits.delete(recipeId, lockId, ifMatch);
    }
}
