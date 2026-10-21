package com.gatar.recipedealer.recipe;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.web.ETags;

@RestController
@RequestMapping("/v1/recipes")
public class RecipeController {

    private final CandidateService candidates;
    private final RecipeQueryService queries;

    public RecipeController(CandidateService candidates, RecipeQueryService queries) {
        this.candidates = candidates;
        this.queries = queries;
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
}
