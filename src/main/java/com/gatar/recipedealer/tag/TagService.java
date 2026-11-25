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

import com.gatar.recipedealer.household.Households;
import com.gatar.recipedealer.recipe.RecipeRepository;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

/**
 * Two-level tags {@code category:tag}, one catalog per household. The catalog (categories and tags in the user's
 * order) is small and is always returned whole. Catalog changes are serialized by one advisory lock; renaming, moving
 * or deleting a tag bumps {@code updated_at} of the recipes carrying it, so they come again in the sync feed with the
 * new ids.
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
    private final Households households;

    public TagService(JdbcClient jdbc, RecipeRepository recipes, Households households) {
        this.jdbc = jdbc;
        this.recipes = recipes;
        this.households = households;
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
    public Catalog catalog(UUID household) {
        Map<String, List<TagView>> tags = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        jdbc.sql("SELECT name, title FROM tag_category WHERE household_id = :household ORDER BY position, name")
                .param("household", household)
                .query(rs -> {
                    titles.put(rs.getString("name"), rs.getString("title"));
                    tags.put(rs.getString("name"), new ArrayList<>());
                });
        jdbc.sql("""
                        SELECT t.category, t.name, t.title,
                               (SELECT count(*) FROM recipe_tag rt JOIN recipe r ON r.id = rt.recipe_id
                                WHERE rt.household_id = t.household_id AND rt.category = t.category AND rt.tag = t.name
                                  AND r.deleted_at IS NULL AND r.merged_into IS NULL) AS recipe_count
                        FROM tag t WHERE t.household_id = :household ORDER BY t.position, t.name""")
                .param("household", household)
                .query(rs -> {
                    String category = rs.getString("category");
                    tags.get(category).add(new TagView(category + ":" + rs.getString("name"), rs.getString("name"),
                            rs.getString("title"), rs.getInt("recipe_count")));
                });
        return new Catalog(titles.entrySet().stream()
                .map(e -> new CategoryView(e.getKey(), e.getValue(), tags.get(e.getKey()))).toList());
    }

    // --- categories ---

    public Catalog createCategory(UUID household, CategoryRequest request) {
        lockCatalog();
        String title = title(request.title());
        String name = request.name() == null || request.name().isBlank() ? slug(title) : name(request.name());
        if (categoryExists(household, name)) {
            throw ApiException.conflict("tag_exists", "category " + name + " already exists");
        }
        jdbc.sql("""
                        INSERT INTO tag_category (household_id, name, title, position)
                        VALUES (:household, :name, :title, (SELECT count(*) FROM tag_category WHERE household_id = :household))""")
                .param("household", household).param("name", name).param("title", title).update();
        if (request.position() != null) {
            moveCategory(household, name, request.position());
        }
        return catalog(household);
    }

    public Catalog updateCategory(UUID household, String category, CategoryRequest request) {
        lockCatalog();
        requireCategory(household, category);
        String current = category;
        if (request.name() != null && !request.name().equals(category)) {
            String name = name(request.name());
            if (categoryExists(household, name)) {
                throw ApiException.conflict("tag_exists", "category " + name + " already exists");
            }
            touchRecipes(household, category, null);
            jdbc.sql("UPDATE tag_category SET name = :name WHERE household_id = :household AND name = :category")
                    .param("household", household).param("name", name).param("category", category).update();
            current = name;
        }
        if (request.title() != null) {
            jdbc.sql("UPDATE tag_category SET title = :title WHERE household_id = :household AND name = :category")
                    .param("household", household).param("title", title(request.title())).param("category", current)
                    .update();
        }
        if (request.position() != null) {
            moveCategory(household, current, request.position());
        }
        return catalog(household);
    }

    /** Deletes the category with its tags and their assignments. */
    public Catalog deleteCategory(UUID household, String category) {
        lockCatalog();
        requireCategory(household, category);
        touchRecipes(household, category, null);
        jdbc.sql("DELETE FROM tag_category WHERE household_id = :household AND name = :category")
                .param("household", household).param("category", category).update();
        renumberCategories(household, categoryNames(household));
        return catalog(household);
    }

    // --- tags ---

    public Catalog createTag(UUID household, String category, TagRequest request) {
        lockCatalog();
        requireCategory(household, category);
        String title = title(request.title());
        String name = request.name() == null || request.name().isBlank() ? slug(title) : name(request.name());
        if (tagExists(household, category, name)) {
            throw ApiException.conflict("tag_exists", "tag " + category + ":" + name + " already exists");
        }
        jdbc.sql("""
                        INSERT INTO tag (household_id, category, name, title, position)
                        VALUES (:household, :category, :name, :title,
                                (SELECT count(*) FROM tag WHERE household_id = :household AND category = :category))""")
                .param("household", household).param("category", category).param("name", name).param("title", title)
                .update();
        if (request.position() != null) {
            moveTag(household, category, name, request.position());
        }
        return catalog(household);
    }

    public Catalog updateTag(UUID household, String category, String tag, TagRequest request) {
        lockCatalog();
        requireTag(household, category, tag);
        String targetCategory = request.category() == null ? category : request.category();
        String targetName = request.name() == null ? tag : name(request.name());
        if (!targetCategory.equals(category)) {
            requireCategory(household, targetCategory);
        }
        if (!targetCategory.equals(category) || !targetName.equals(tag)) {
            if (tagExists(household, targetCategory, targetName)) {
                throw ApiException.conflict("tag_exists", "tag " + targetCategory + ":" + targetName + " already exists");
            }
            touchRecipes(household, category, tag);
            jdbc.sql("""
                            UPDATE tag SET category = :targetCategory, name = :targetName,
                                   position = CASE WHEN :targetCategory = :category THEN position
                                                   ELSE (SELECT count(*) FROM tag
                                                         WHERE household_id = :household AND category = :targetCategory) END
                            WHERE household_id = :household AND category = :category AND name = :tag""")
                    .param("household", household)
                    .param("targetCategory", targetCategory).param("targetName", targetName)
                    .param("category", category).param("tag", tag).update();
            if (!targetCategory.equals(category)) {
                renumberTags(household, category, tagNames(household, category));
            }
        }
        if (request.title() != null) {
            jdbc.sql("UPDATE tag SET title = :title WHERE household_id = :household AND category = :category AND name = :tag")
                    .param("household", household).param("title", title(request.title()))
                    .param("category", targetCategory).param("tag", targetName).update();
        }
        if (request.position() != null) {
            moveTag(household, targetCategory, targetName, request.position());
        }
        return catalog(household);
    }

    public Catalog deleteTag(UUID household, String category, String tag) {
        lockCatalog();
        requireTag(household, category, tag);
        touchRecipes(household, category, tag);
        jdbc.sql("DELETE FROM tag WHERE household_id = :household AND category = :category AND name = :tag")
                .param("household", household).param("category", category).param("tag", tag).update();
        renumberTags(household, category, tagNames(household, category));
        return catalog(household);
    }

    // --- assignments ---

    /** Ids of the recipe's tags in catalog order. */
    @Transactional(readOnly = true)
    public List<String> tagsOf(UUID recipeId) {
        return jdbc.sql("""
                        SELECT rt.category || ':' || rt.tag
                        FROM recipe_tag rt
                             JOIN tag t ON t.household_id = rt.household_id AND t.category = rt.category AND t.name = rt.tag
                             JOIN tag_category c ON c.household_id = rt.household_id AND c.name = rt.category
                        WHERE rt.recipe_id = :id
                        ORDER BY c.position, t.position""")
                .param("id", recipeId).query(String.class).list();
    }

    /** Replaces the tags of a recipe of the household; bumps the recipe in the sync feed when they changed. */
    public List<String> setTags(UUID household, UUID recipeId, List<String> ids) {
        jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock_shared(:key)) l").param("key", CATALOG_LOCK)
                .query(Long.class).single();
        var head = recipes.lockHead(recipeId).orElseThrow(() -> new NotFoundException("recipe " + recipeId + " not found"));
        if (head.deleted() || head.mergedInto() != null) {
            throw new ApiException(HttpStatus.GONE, "recipe_gone", "recipe " + recipeId + " was deleted or merged");
        }
        Set<String> wanted = requireExisting(household, ids);
        if (!wanted.equals(new LinkedHashSet<>(tagsOf(recipeId)))) {
            jdbc.sql("DELETE FROM recipe_tag WHERE recipe_id = :id").param("id", recipeId).update();
            insert(household, recipeId, wanted);
            jdbc.sql("UPDATE recipe SET updated_at = now() WHERE id = :id").param("id", recipeId).update();
        }
        return tagsOf(recipeId);
    }

    /** Ids that exist in the household's catalog, deduplicated; 400 {@code unknown_tag} otherwise. */
    @Transactional(readOnly = true)
    public Set<String> requireExisting(UUID household, Collection<String> ids) {
        Set<String> wanted = new LinkedHashSet<>(ids == null ? List.of() : ids);
        for (String id : wanted) {
            int colon = id == null ? -1 : id.indexOf(':');
            if (colon < 0 || !tagExists(household, id.substring(0, colon), id.substring(colon + 1))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_tag", "unknown tag " + id);
            }
        }
        return wanted;
    }

    /** Tags chosen with a NEW decision; tags deleted in the meantime are skipped. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assignToNewRecipe(UUID household, UUID recipeId, Collection<String> ids) {
        insert(household, recipeId, ids);
    }

    /**
     * Tags chosen by {@link AutoTagger}, assigned only to a live recipe that has no tags yet (the user may have tagged
     * it in the meantime); tags deleted in the meantime are skipped. Bumps the recipe in the sync feed.
     *
     * @return false if nothing was assigned for that reason
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean assignAutomatically(UUID recipeId, Collection<String> ids) {
        jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock_shared(:key)) l").param("key", CATALOG_LOCK)
                .query(Long.class).single();
        var head = recipes.lockHead(recipeId);
        if (head.isEmpty() || head.get().deleted() || head.get().mergedInto() != null || !tagsOf(recipeId).isEmpty()) {
            return false;
        }
        insert(households.ofRecipe(recipeId), recipeId, ids);
        if (!tagsOf(recipeId).isEmpty()) {
            jdbc.sql("UPDATE recipe SET updated_at = now() WHERE id = :id").param("id", recipeId).update();
        }
        return true;
    }

    private void insert(UUID household, UUID recipeId, Collection<String> ids) {
        for (String id : ids) {
            int colon = id.indexOf(':');
            if (colon < 0) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO recipe_tag (household_id, recipe_id, category, tag)
                            SELECT household_id, :id, category, name FROM tag
                            WHERE household_id = :household AND category = :category AND name = :tag
                            ON CONFLICT DO NOTHING""")
                    .param("household", household).param("id", recipeId)
                    .param("category", id.substring(0, colon)).param("tag", id.substring(colon + 1))
                    .update();
        }
    }

    // --- helpers ---

    private void lockCatalog() {
        jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) l").param("key", CATALOG_LOCK).query(Long.class).single();
    }

    private void touchRecipes(UUID household, String category, String tag) {
        jdbc.sql("""
                        UPDATE recipe SET updated_at = now()
                        WHERE id IN (SELECT recipe_id FROM recipe_tag
                                     WHERE household_id = :household AND category = :category
                                       AND (CAST(:tag AS text) IS NULL OR tag = :tag))""")
                .param("household", household).param("category", category).param("tag", tag).update();
    }

    private void moveCategory(UUID household, String name, int position) {
        List<String> names = new ArrayList<>(categoryNames(household));
        names.remove(name);
        names.add(Math.clamp(position, 0, names.size()), name);
        renumberCategories(household, names);
    }

    private void moveTag(UUID household, String category, String name, int position) {
        List<String> names = new ArrayList<>(tagNames(household, category));
        names.remove(name);
        names.add(Math.clamp(position, 0, names.size()), name);
        renumberTags(household, category, names);
    }

    private void renumberCategories(UUID household, List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            jdbc.sql("UPDATE tag_category SET position = :position WHERE household_id = :household AND name = :name")
                    .param("household", household).param("position", i).param("name", names.get(i)).update();
        }
    }

    private void renumberTags(UUID household, String category, List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            jdbc.sql("""
                            UPDATE tag SET position = :position
                            WHERE household_id = :household AND category = :category AND name = :name""")
                    .param("household", household).param("position", i).param("category", category)
                    .param("name", names.get(i)).update();
        }
    }

    private List<String> categoryNames(UUID household) {
        return jdbc.sql("SELECT name FROM tag_category WHERE household_id = :household ORDER BY position, name")
                .param("household", household).query(String.class).list();
    }

    private List<String> tagNames(UUID household, String category) {
        return jdbc.sql("SELECT name FROM tag WHERE household_id = :household AND category = :category ORDER BY position, name")
                .param("household", household).param("category", category).query(String.class).list();
    }

    private boolean categoryExists(UUID household, String name) {
        return jdbc.sql("SELECT count(*) FROM tag_category WHERE household_id = :household AND name = :name")
                .param("household", household).param("name", name).query(Long.class).single() > 0;
    }

    private boolean tagExists(UUID household, String category, String name) {
        return jdbc.sql("SELECT count(*) FROM tag WHERE household_id = :household AND category = :category AND name = :name")
                .param("household", household).param("category", category).param("name", name)
                .query(Long.class).single() > 0;
    }

    private void requireCategory(UUID household, String name) {
        if (!categoryExists(household, name)) {
            throw new NotFoundException("tag category " + name + " not found");
        }
    }

    private void requireTag(UUID household, String category, String name) {
        if (!tagExists(household, category, name)) {
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
