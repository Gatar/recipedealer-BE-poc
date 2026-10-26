package com.gatar.recipedealer.recipe;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.recipe.RecipeRepository.Origin;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.JsonNode;

/**
 * The only place that creates revisions and moves heads.
 * <ul>
 *   <li>Equal content to the head: no new revision, unless an explicit user action confirms a head that needs
 *       review (the new revision has the same content without {@code needsReview}).</li>
 *   <li>An explicit user action (resolution, edit) always moves the head.</li>
 *   <li>An automatic change never moves a head with origin {@code USER}; it is kept as a proposal.</li>
 * </ul>
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RevisionService {

    private final RecipeRepository recipes;

    public RevisionService(RecipeRepository recipes) {
        this.recipes = recipes;
    }

    /**
     * Revision content and its source. {@code schemaVersion}: the contract version {@code recipe} follows;
     * {@code runId} for machine revisions, {@code userId} for user revisions.
     */
    public record Content(JsonNode recipe, int schemaVersion, Origin origin, UUID runId, UUID captureId, UUID userId,
                          UUID deviceId, boolean needsReview) {
    }

    public record Outcome(UUID recipeId, UUID revisionId, boolean created, boolean headMoved) {
    }

    public Outcome createRecipe(Content content) {
        UUID recipeId = Ids.newId();
        recipes.insertRecipe(recipeId);
        UUID revisionId = insert(recipeId, null, content, RecipeContent.contentHash(content.recipe()));
        recipes.moveHead(recipeId, revisionId, content.recipe(), content.captureId());
        return new Outcome(recipeId, revisionId, true, true);
    }

    public Outcome addRevision(UUID recipeId, Content content, boolean explicitUserAction) {
        var head = recipes.lockHead(recipeId).orElseThrow(() -> new NotFoundException("recipe " + recipeId + " not found"));
        if (head.deleted() || head.mergedInto() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "recipe_gone",
                    "recipe " + recipeId + (head.deleted() ? " was deleted" : " was merged into " + head.mergedInto()));
        }
        String hash = RecipeContent.contentHash(content.recipe());
        boolean confirmsReview = explicitUserAction && head.headNeedsReview() && !content.needsReview();
        if (hash.equals(head.headContentHash()) && !confirmsReview) {
            return new Outcome(recipeId, head.headRevisionId(), false, false);
        }
        boolean moveHead = explicitUserAction || head.headOrigin() != Origin.USER;
        if (!moveHead) {
            var existing = recipes.findChild(head.headRevisionId(), hash);
            if (existing.isPresent()) {
                return new Outcome(recipeId, existing.get(), false, false);
            }
        }
        UUID revisionId = insert(recipeId, head.headRevisionId(), content, hash);
        if (moveHead) {
            recipes.moveHead(recipeId, revisionId, content.recipe(), content.captureId());
        }
        return new Outcome(recipeId, revisionId, true, moveHead);
    }

    private UUID insert(UUID recipeId, UUID parentId, Content content, String hash) {
        UUID revisionId = Ids.newId();
        recipes.insertRevision(new RecipeRepository.NewRevision(revisionId, recipeId, parentId, content.origin(),
                content.runId(), content.captureId(), content.userId(), content.deviceId(), content.schemaVersion(),
                content.recipe(), hash, content.needsReview()));
        return revisionId;
    }
}
