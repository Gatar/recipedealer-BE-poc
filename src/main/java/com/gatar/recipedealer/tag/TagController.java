package com.gatar.recipedealer.tag;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.household.Households;

/** The household's tag catalog (every change answers with the whole catalog) and the tags of a recipe. */
@RestController
public class TagController {

    private final TagService tags;
    private final Households households;

    public TagController(TagService tags, Households households) {
        this.tags = tags;
        this.households = households;
    }

    public record RecipeTags(List<String> tags) {
    }

    @GetMapping("/v1/tags")
    public TagService.Catalog catalog(@AuthenticationPrincipal DevicePrincipal device) {
        return tags.catalog(device.householdId());
    }

    @PostMapping("/v1/tags/categories")
    public TagService.Catalog createCategory(@RequestBody TagService.CategoryRequest request,
                                             @AuthenticationPrincipal DevicePrincipal device) {
        return tags.createCategory(device.householdId(), request);
    }

    @PatchMapping("/v1/tags/categories/{category}")
    public TagService.Catalog updateCategory(@PathVariable String category, @RequestBody TagService.CategoryRequest request,
                                             @AuthenticationPrincipal DevicePrincipal device) {
        return tags.updateCategory(device.householdId(), category, request);
    }

    @DeleteMapping("/v1/tags/categories/{category}")
    public TagService.Catalog deleteCategory(@PathVariable String category,
                                             @AuthenticationPrincipal DevicePrincipal device) {
        return tags.deleteCategory(device.householdId(), category);
    }

    @PostMapping("/v1/tags/categories/{category}/tags")
    public TagService.Catalog createTag(@PathVariable String category, @RequestBody TagService.TagRequest request,
                                        @AuthenticationPrincipal DevicePrincipal device) {
        return tags.createTag(device.householdId(), category, request);
    }

    @PatchMapping("/v1/tags/categories/{category}/tags/{tag}")
    public TagService.Catalog updateTag(@PathVariable String category, @PathVariable String tag,
                                        @RequestBody TagService.TagRequest request,
                                        @AuthenticationPrincipal DevicePrincipal device) {
        return tags.updateTag(device.householdId(), category, tag, request);
    }

    @DeleteMapping("/v1/tags/categories/{category}/tags/{tag}")
    public TagService.Catalog deleteTag(@PathVariable String category, @PathVariable String tag,
                                        @AuthenticationPrincipal DevicePrincipal device) {
        return tags.deleteTag(device.householdId(), category, tag);
    }

    /** Replaces the recipe's tags (no edit lock, no revision); answers with them in catalog order. */
    @PutMapping("/v1/recipes/{recipeId}/tags")
    public RecipeTags setRecipeTags(@PathVariable UUID recipeId, @RequestBody RecipeTags request,
                                    @AuthenticationPrincipal DevicePrincipal device) {
        households.requireRecipe(device.householdId(), recipeId);
        return new RecipeTags(tags.setTags(device.householdId(), recipeId, request.tags()));
    }
}
