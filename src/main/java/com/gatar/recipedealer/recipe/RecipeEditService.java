package com.gatar.recipedealer.recipe;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.capture.CaptureSchemaValidator;
import com.gatar.recipedealer.lock.LockService;
import com.gatar.recipedealer.recipe.RecipeRepository.Origin;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.ETags;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.JsonNode;

/** Online editing: a save needs the active edit lock and the head the editor started from. */
@Service
public class RecipeEditService {

    private final CaptureSchemaValidator validator;
    private final LockService locks;
    private final RecipeRepository recipes;
    private final RevisionService revisions;
    private final JdbcClient jdbc;

    public RecipeEditService(CaptureSchemaValidator validator, LockService locks, RecipeRepository recipes,
                             RevisionService revisions, JdbcClient jdbc) {
        this.validator = validator;
        this.locks = locks;
        this.recipes = recipes;
        this.revisions = revisions;
        this.jdbc = jdbc;
    }

    /** Saves a USER revision as the new head and releases the lock, in one transaction. */
    @Transactional
    public RevisionService.Outcome save(UUID recipeId, UUID lockId, String ifMatch, JsonNode recipe, DevicePrincipal device) {
        if (recipe == null || !recipe.isObject()) {
            throw ApiException.badRequest("body must be a recipe object");
        }
        validator.validateRecipe(recipe);
        var head = recipes.lockHead(recipeId).orElseThrow(() -> new NotFoundException("recipe " + recipeId + " not found"));
        if (head.deleted() || head.mergedInto() != null) {
            throw new ApiException(HttpStatus.GONE, "recipe_gone", "recipe " + recipeId + " was deleted or merged");
        }
        locks.consume(recipeId, lockId);
        requireHead(ifMatch, head.headRevisionId());
        var content = new RevisionService.Content(recipe, Origin.USER, null, null, device.userId(), device.deviceId(), false);
        return revisions.addRevision(recipeId, content, true);
    }

    /** Soft delete: the recipe becomes a tombstone in the sync feed and its identity keys are freed. */
    @Transactional
    public void delete(UUID recipeId, UUID lockId, String ifMatch) {
        var head = recipes.lockHead(recipeId).orElseThrow(() -> new NotFoundException("recipe " + recipeId + " not found"));
        if (head.deleted() || head.mergedInto() != null) {
            return;
        }
        locks.consume(recipeId, lockId);
        if (ifMatch != null) {
            requireHead(ifMatch, head.headRevisionId());
        }
        jdbc.sql("DELETE FROM recipe_key WHERE recipe_id = :id").param("id", recipeId).update();
        jdbc.sql("UPDATE recipe SET deleted_at = now(), updated_at = now() WHERE id = :id").param("id", recipeId).update();
    }

    private static void requireHead(String ifMatch, UUID headRevisionId) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "if_match_required",
                    "If-Match with the head revision id is required");
        }
        if (ifMatch.strip().equals("*") || !ETags.matches(ifMatch, headRevisionId)) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "head_changed",
                    "the recipe was changed since it was opened; current head is " + headRevisionId);
        }
    }
}
