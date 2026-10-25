package com.gatar.recipedealer.tag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.recipe.RecipeRepository;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

/**
 * Two-level tags {@code category:tag}. The catalog (categories and tags in the user's order) is small and is always
 * returned whole. Catalog changes are serialized by one advisory lock; renaming, moving or deleting a tag bumps
 * {@code updated_at} of the recipes carrying it, so they come again in the sync feed with the new ids.
 */
@Service
@Transactional
public class TagService {

    public static final int MAX_NAME = 40;
    public static final int MAX_TITLE = 60;
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final long CATALOG_LOCK = 0x7461_6773L;

    private final JdbcClient jdbc;
    private final RecipeRepository recipes;

    public TagService(JdbcClient jdbc, RecipeRepository recipes) {
        this.jdbc = jdbc;
        this.recipes = recipes;
    }

    /** {@code recipeCount}: live recipes carrying the tag. */
    public record TagView(String id, String name, String title, int recipeCount) {
    }

    public record CategoryView(String name, String title, List<TagView> tags) {
    }

    public record Catalog(List<CategoryView> categories) {
    }

    /** Create or change a category; {@code null} fields stay unchanged. {@code position} is 0-based. */
    public record CategoryRequest(String name, String title, Integer position) {
    }

    /** Create or change a tag; {@code category} moves it to another category. */
    public record TagRequest(String name, String title, String category, Integer position) {
    }

    @Transactional(readOnly = true)
    public Catalog catalog() {
        Map<String, List<TagView>> tags = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        jdbc.sql("SELECT name, title FROM tag_category ORDER BY position, name")
                .query(rs -> {
                    titles.put(rs.getString("name"), rs.getString("title"));
                    tags.put(rs.getString("name"), new ArrayList<>());
                });
        jdbc.sql("""
                        SELECT t.category, t.name, t.title,
                               (SELECT count(*) FROM recipe_tag rt JOIN recipe r ON r.id = rt.recipe_id
                                WHERE rt.category = t.category AND rt.tag = t.name
                                  AND r.deleted_at IS NULL AND r.merged_into IS NULL) AS recipe_count
                        FROM tag t ORDER BY t.position, t.name""")
                .query(rs -> {
                    String category = rs.getString("category");
                    tags.get(category).add(new TagView(category + ":" + rs.getString("name"), rs.getString("name"),
                            rs.getString("title"), rs.getInt("recipe_count")));
                });
        return new Catalog(titles.entrySet().stream()
                .map(e -> new CategoryView(e.getKey(), e.getValue(), tags.get(e.getKey()))).toList());
    }

    // --- categories ---

    public Catalog createCategory(CategoryRequest request) {
        lockCatalog();
        String title = title(request.title());
        String name = request.name() == null || request.name().isBlank() ? slug(title) : name(request.name());
        if (categoryExists(name)) {
            throw ApiException.conflict("tag_exists", "category " + name + " already exists");
        }
        jdbc.sql("INSERT INTO tag_category (name, title, position) VALUES (:name, :title, (SELECT count(*) FROM tag_category))")
                .param("name", name).param("title", title).update();
        if (request.position() != null) {
            moveCategory(name, request.position());
        }
        return catalog();
    }

    public Catalog updateCategory(String category, CategoryRequest request) {
        lockCatalog();
        requireCategory(category);
        String current = category;
        if (request.name() != null && !request.name().equals(category)) {
            String name = name(request.name());
            if (categoryExists(name)) {
                throw ApiException.conflict("tag_exists", "category " + name + " already exists");
            }
            touchRecipes(category, null);
            jdbc.sql("UPDATE tag_category SET name = :name WHERE name = :category")
                    .param("name", name).param("category", category).update();
            current = name;
        }
        if (request.title() != null) {
            jdbc.sql("UPDATE tag_category SET title = :title WHERE name = :category")
                    .param("title", title(request.title())).param("category", current).update();
        }
        if (request.position() != null) {
            moveCategory(current, request.position());
        }
        return catalog();
    }

    /** Deletes the category with its tags and their assignments. */
    public Catalog deleteCategory(String category) {
        lockCatalog();
        requireCategory(category);
        touchRecipes(category, null);
        jdbc.sql("DELETE FROM tag_category WHERE name = :category").param("category", category).update();
        renumberCategories(categoryNames());
        return catalog();
    }

    // --- tags ---

    public Catalog createTag(String category, TagRequest request) {
        lockCatalog();
        requireCategory(category);
        String title = title(request.title());
        String name = request.name() == null || request.name().isBlank() ? slug(title) : name(request.name());
        if (tagExists(category, name)) {
            throw ApiException.conflict("tag_exists", "tag " + category + ":" + name + " already exists");
        }
        jdbc.sql("""
                        INSERT INTO tag (category, name, title, position)
                        VALUES (:category, :name, :title, (SELECT count(*) FROM tag WHERE category = :category))""")
                .param("category", category).param("name", name).param("title", title).update();
        if (request.position() != null) {
            moveTag(category, name, request.position());
        }
        return catalog();
    }

    public Catalog updateTag(String category, String tag, TagRequest request) {
        lockCatalog();
        requireTag(category, tag);
        String targetCategory = request.category() == null ? category : request.category();
        String targetName = request.name() == null ? tag : name(request.name());
        if (!targetCategory.equals(category)) {
            requireCategory(targetCategory);
        }
        if (!targetCategory.equals(category) || !targetName.equals(tag)) {
            if (tagExists(targetCategory, targetName)) {
                throw ApiException.conflict("tag_exists", "tag " + targetCategory + ":" + targetName + " already exists");
            }
            touchRecipes(category, tag);
            jdbc.sql("""
                            UPDATE tag SET category = :targetCategory, name = :targetName,
                                   position = CASE WHEN :targetCategory = :category THEN position
                                                   ELSE (SELECT count(*) FROM tag WHERE category = :targetCategory) END
                            WHERE category = :category AND name = :tag""")
                    .param("targetCategory", targetCategory).param("targetName", targetName)
                    .param("category", category).param("tag", tag).update();
            if (!targetCategory.equals(category)) {
                renumberTags(category, tagNames(category));
            }
        }
        if (request.title() != null) {
            jdbc.sql("UPDATE tag SET title = :title WHERE category = :category AND name = :tag")
                    .param("title", title(request.title()))
                    .param("category", targetCategory).param("tag", targetName).update();
        }
        if (request.position() != null) {
            moveTag(targetCategory, targetName, request.position());
        }
        return catalog();
    }

    public Catalog deleteTag(String category, String tag) {
        lockCatalog();
        requireTag(category, tag);
        touchRecipes(category, tag);
        jdbc.sql("DELETE FROM tag WHERE category = :category AND name = :tag")
                .param("category", category).param("tag", tag).update();
        renumberTags(category, tagNames(category));
        return catalog();
    }

    // --- assignments ---

    /** Ids of the recipe's tags in catalog order. */
    @Transactional(readOnly = true)
    public List<String> tagsOf(UUID recipeId) {
        return jdbc.sql("""
                        SELECT rt.category || ':' || rt.tag
                        FROM recipe_tag rt JOIN tag t ON t.category = rt.category AND t.name = rt.tag
                             JOIN tag_category c ON c.name = rt.category
                        WHERE rt.recipe_id = :id
                        ORDER BY c.position, t.position""")
                .param("id", recipeId).query(String.class).list();
    }

    /** Replaces the recipe's tags; bumps the recipe in the sync feed when they changed. */
    public List<String> setTags(UUID recipeId, List<String> ids) {
        jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock_shared(:key)) l").param("key", CATALOG_LOCK)
                .query(Long.class).single();
        var head = recipes.lockHead(recipeId).orElseThrow(() -> new NotFoundException("recipe " + recipeId + " not found"));
        if (head.deleted() || head.mergedInto() != null) {
            throw new ApiException(HttpStatus.GONE, "recipe_gone", "recipe " + recipeId + " was deleted or merged");
        }
        Set<String> wanted = requireExisting(ids);
        if (!wanted.equals(new LinkedHashSet<>(tagsOf(recipeId)))) {
            jdbc.sql("DELETE FROM recipe_tag WHERE recipe_id = :id").param("id", recipeId).update();
            insert(recipeId, wanted);
            jdbc.sql("UPDATE recipe SET updated_at = now() WHERE id = :id").param("id", recipeId).update();
        }
        return tagsOf(recipeId);
    }

    /** Ids that exist, deduplicated; 400 {@code unknown_tag} otherwise. */
    @Transactional(readOnly = true)
    public Set<String> requireExisting(Collection<String> ids) {
        Set<String> wanted = new LinkedHashSet<>(ids == null ? List.of() : ids);
        for (String id : wanted) {
            int colon = id == null ? -1 : id.indexOf(':');
            if (colon < 0 || !tagExists(id.substring(0, colon), id.substring(colon + 1))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_tag", "unknown tag " + id);
            }
        }
        return wanted;
    }

    /** Tags chosen with a NEW decision; tags deleted in the meantime are skipped. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assignToNewRecipe(UUID recipeId, Collection<String> ids) {
        insert(recipeId, ids);
    }

    private void insert(UUID recipeId, Collection<String> ids) {
        for (String id : ids) {
            int colon = id.indexOf(':');
            if (colon < 0) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO recipe_tag (recipe_id, category, tag)
                            SELECT :id, category, name FROM tag WHERE category = :category AND name = :tag
                            ON CONFLICT DO NOTHING""")
                    .param("id", recipeId).param("category", id.substring(0, colon)).param("tag", id.substring(colon + 1))
                    .update();
        }
    }

    // --- helpers ---

    private void lockCatalog() {
        jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) l").param("key", CATALOG_LOCK).query(Long.class).single();
    }

    private void touchRecipes(String category, String tag) {
        jdbc.sql("""
                        UPDATE recipe SET updated_at = now()
                        WHERE id IN (SELECT recipe_id FROM recipe_tag
                                     WHERE category = :category AND (CAST(:tag AS text) IS NULL OR tag = :tag))""")
                .param("category", category).param("tag", tag).update();
    }

    private void moveCategory(String name, int position) {
        List<String> names = new ArrayList<>(categoryNames());
        names.remove(name);
        names.add(Math.clamp(position, 0, names.size()), name);
        renumberCategories(names);
    }

    private void moveTag(String category, String name, int position) {
        List<String> names = new ArrayList<>(tagNames(category));
        names.remove(name);
        names.add(Math.clamp(position, 0, names.size()), name);
        renumberTags(category, names);
    }

    private void renumberCategories(List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            jdbc.sql("UPDATE tag_category SET position = :position WHERE name = :name")
                    .param("position", i).param("name", names.get(i)).update();
        }
    }

    private void renumberTags(String category, List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            jdbc.sql("UPDATE tag SET position = :position WHERE category = :category AND name = :name")
                    .param("position", i).param("category", category).param("name", names.get(i)).update();
        }
    }

    private List<String> categoryNames() {
        return jdbc.sql("SELECT name FROM tag_category ORDER BY position, name").query(String.class).list();
    }

    private List<String> tagNames(String category) {
        return jdbc.sql("SELECT name FROM tag WHERE category = :category ORDER BY position, name")
                .param("category", category).query(String.class).list();
    }

    private boolean categoryExists(String name) {
        return jdbc.sql("SELECT count(*) FROM tag_category WHERE name = :name").param("name", name)
                .query(Long.class).single() > 0;
    }

    private boolean tagExists(String category, String name) {
        return jdbc.sql("SELECT count(*) FROM tag WHERE category = :category AND name = :name")
                .param("category", category).param("name", name).query(Long.class).single() > 0;
    }

    private void requireCategory(String name) {
        if (!categoryExists(name)) {
            throw new NotFoundException("tag category " + name + " not found");
        }
    }

    private void requireTag(String category, String name) {
        if (!tagExists(category, name)) {
            throw new NotFoundException("tag " + category + ":" + name + " not found");
        }
    }

    private static String name(String name) {
        if (name == null || name.length() > MAX_NAME || !NAME.matcher(name).matches()) {
            throw ApiException.badRequest("name must be lower-case letters, digits and single hyphens, at most "
                    + MAX_NAME + " characters: " + name);
        }
        return name;
    }

    private static String title(String title) {
        String stripped = title == null ? "" : title.strip();
        if (stripped.isEmpty()) {
            throw ApiException.badRequest("title is required");
        }
        if (stripped.length() > MAX_TITLE) {
            throw ApiException.badRequest("title must be at most " + MAX_TITLE + " characters");
        }
        return stripped;
    }

    /** Name derived from a title: "Danie główne" → "danie-glowne". */
    static String slug(String title) {
        String s = Normalizer.normalize(title.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > MAX_NAME) {
            s = s.substring(0, MAX_NAME).replaceAll("-+$", "");
        }
        if (s.isEmpty()) {
            throw ApiException.badRequest("cannot derive a name from the title; give a name");
        }
        return s;
    }
}
