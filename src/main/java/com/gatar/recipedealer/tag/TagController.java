package com.gatar.recipedealer.tag;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The tag catalog (every change answers with the whole catalog) and the tags of a recipe. */
@RestController
public class TagController {

    private final TagService tags;

    public TagController(TagService tags) {
        this.tags = tags;
    }

    public record RecipeTags(List<String> tags) {
    }

    @GetMapping("/v1/tags")
    public TagService.Catalog catalog() {
        return tags.catalog();
    }

    @PostMapping("/v1/tags/categories")
    public TagService.Catalog createCategory(@RequestBody TagService.CategoryRequest request) {
        return tags.createCategory(request);
    }

    @PatchMapping("/v1/tags/categories/{category}")
    public TagService.Catalog updateCategory(@PathVariable String category, @RequestBody TagService.CategoryRequest request) {
        return tags.updateCategory(category, request);
    }

    @DeleteMapping("/v1/tags/categories/{category}")
    public TagService.Catalog deleteCategory(@PathVariable String category) {
        return tags.deleteCategory(category);
    }

    @PostMapping("/v1/tags/categories/{category}/tags")
    public TagService.Catalog createTag(@PathVariable String category, @RequestBody TagService.TagRequest request) {
        return tags.createTag(category, request);
    }

    @PatchMapping("/v1/tags/categories/{category}/tags/{tag}")
    public TagService.Catalog updateTag(@PathVariable String category, @PathVariable String tag,
                                        @RequestBody TagService.TagRequest request) {
        return tags.updateTag(category, tag, request);
    }

    @DeleteMapping("/v1/tags/categories/{category}/tags/{tag}")
    public TagService.Catalog deleteTag(@PathVariable String category, @PathVariable String tag) {
        return tags.deleteTag(category, tag);
    }

    /** Replaces the recipe's tags (no edit lock, no revision); answers with them in catalog order. */
    @PutMapping("/v1/recipes/{recipeId}/tags")
    public RecipeTags setRecipeTags(@PathVariable UUID recipeId, @RequestBody RecipeTags request) {
        return new RecipeTags(tags.setTags(recipeId, request.tags()));
    }
}
