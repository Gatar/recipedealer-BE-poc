package com.gatar.recipedealer.admin;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.household.HouseholdService;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-mostly views for the admin dashboard ({@code /admin/}) across all households; the only change here is a user's
 * role. Everything else the dashboard changes goes through the existing admin endpoints.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminDashboardController {

    public static final int MAX_ROWS = 500;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final BlobStore blobs;

    public AdminDashboardController(JdbcClient jdbc, JsonMapper jsonMapper, BlobStore blobs) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.blobs = blobs;
    }

    // --- overview ---

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Overview(Instant serverTime, long households, long users, long devices, long recipes,
                           long deletedRecipes, long captures, Map<String, Long> capturesByStatus, long blobs,
                           long blobBytes, BigDecimal llmCostThisMonthUsd, BigDecimal llmCostTotalUsd,
                           ApiKeyCheckView lastApiKeyCheck) {
    }

    public record ApiKeyCheckView(Instant checkedAt, boolean success, String errorType) {
    }

    @GetMapping("/overview")
    public Overview overview() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM capture_state GROUP BY status ORDER BY status")
                .query(rs -> {
                    byStatus.put(rs.getString("status"), rs.getLong("n"));
                });
        ApiKeyCheckView check = jdbc.sql("SELECT checked_at, success, error_type FROM api_key_check ORDER BY checked_at DESC LIMIT 1")
                .query((rs, n) -> new ApiKeyCheckView(Jdbc.instant(rs, "checked_at"), rs.getBoolean("success"),
                        rs.getString("error_type")))
                .optional().orElse(null);
        return jdbc.sql("""
                        SELECT now() AS server_time,
                               (SELECT count(*) FROM household WHERE deleted_at IS NULL) AS households,
                               (SELECT count(*) FROM app_user WHERE deleted_at IS NULL) AS users,
                               (SELECT count(*) FROM device_token d JOIN app_user u ON u.id = d.user_id
                                WHERE d.revoked_at IS NULL AND u.deleted_at IS NULL) AS devices,
                               (SELECT count(*) FROM recipe WHERE deleted_at IS NULL AND merged_into IS NULL) AS recipes,
                               (SELECT count(*) FROM recipe WHERE deleted_at IS NOT NULL OR merged_into IS NOT NULL) AS deleted,
                               (SELECT count(*) FROM capture) AS captures,
                               (SELECT count(*) FROM blob) AS blobs,
                               (SELECT coalesce(sum(size_bytes), 0) FROM blob) AS blob_bytes,
                               (%1$s AND c.started_at >= %2$s) AS month_cost,
                               (%1$s) AS total_cost""".formatted(COST_SUM, MONTH))
                .query((rs, n) -> new Overview(Jdbc.instant(rs, "server_time"), rs.getLong("households"),
                        rs.getLong("users"), rs.getLong("devices"), rs.getLong("recipes"), rs.getLong("deleted"),
                        rs.getLong("captures"), byStatus, rs.getLong("blobs"), rs.getLong("blob_bytes"),
                        rs.getBigDecimal("month_cost"), rs.getBigDecimal("total_cost"), check))
                .single();
    }

    /** All Claude calls with their cost: extraction runs (comparisons included), taggings, translations, key checks. */
    private static final String CALLS = """
            SELECT r.started_at, c.household_id, 'extraction' AS kind, r.model, r.cost_usd
            FROM processing_run r JOIN capture c ON c.id = r.capture_id WHERE r.cost_usd IS NOT NULL
            UNION ALL
            SELECT t.created_at, c.household_id, 'tagging', t.model, t.cost_usd
            FROM auto_tagging t JOIN capture c ON c.id = t.capture_id WHERE t.cost_usd IS NOT NULL
            UNION ALL
            SELECT t.created_at, c.household_id, 'translation', t.model, t.cost_usd
            FROM auto_translation t JOIN capture c ON c.id = t.capture_id WHERE t.cost_usd IS NOT NULL
            UNION ALL
            SELECT k.checked_at, NULL, 'api-key-check', k.model, k.cost_usd FROM api_key_check k WHERE k.cost_usd IS NOT NULL""";
    private static final String COST_SUM = "SELECT coalesce(sum(c.cost_usd), 0) FROM (" + CALLS + ") c WHERE true";
    private static final String MONTH = "date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'";

    // --- costs ---

    /** {@code householdName} null: calls of no household (API key checks). */
    public record CostRow(String month, UUID householdId, String householdName, String kind, String model, long calls,
                          BigDecimal costUsd) {
    }

    /** Claude costs per calendar month (UTC), household, kind and model, newest month first. */
    @GetMapping("/llm-costs")
    public List<CostRow> costs(@RequestParam(defaultValue = "6") int months) {
        if (months < 1 || months > 120) {
            throw ApiException.badRequest("months must be between 1 and 120");
        }
        return jdbc.sql("""
                        SELECT to_char(date_trunc('month', c.started_at AT TIME ZONE 'UTC'), 'YYYY-MM') AS month,
                               c.household_id, h.name, c.kind, c.model, count(*) AS calls, sum(c.cost_usd) AS cost
                        FROM (%s) c LEFT JOIN household h ON h.id = c.household_id
                        WHERE c.started_at >= %s - make_interval(months => :months - 1)
                        GROUP BY 1, 2, 3, 4, 5
                        ORDER BY 1 DESC, 7 DESC""".formatted(CALLS, MONTH))
                .param("months", months)
                .query((rs, n) -> new CostRow(rs.getString("month"), Jdbc.uuid(rs, "household_id"), rs.getString("name"),
                        rs.getString("kind"), rs.getString("model"), rs.getLong("calls"), rs.getBigDecimal("cost")))
                .list();
    }

    // --- users ---

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminUser(UUID userId, String userName, UUID householdId, String householdName, String role,
                            boolean hasPassword, int devices, Instant createdAt, Instant lastUsedAt, Instant deletedAt) {
    }

    /** Users, newest first; deleted ones only with {@code deleted=true}. */
    @GetMapping("/users")
    public List<AdminUser> users(@RequestParam(required = false) UUID householdId,
                                 @RequestParam(defaultValue = "false") boolean deleted) {
        return jdbc.sql("""
                        SELECT u.id, u.name, u.household_id, h.name AS household_name, u.role,
                               u.password_hash IS NOT NULL AS has_password, u.created_at, u.deleted_at,
                               (SELECT count(*) FROM device_token d WHERE d.user_id = u.id AND d.revoked_at IS NULL) AS devices,
                               (SELECT max(d.last_used_at) FROM device_token d WHERE d.user_id = u.id) AS last_used_at
                        FROM app_user u JOIN household h ON h.id = u.household_id
                        WHERE (CAST(:household AS uuid) IS NULL OR u.household_id = :household)
                          AND (:deleted OR u.deleted_at IS NULL)
                        ORDER BY u.created_at DESC
                        LIMIT %d""".formatted(MAX_ROWS))
                .param("household", householdId).param("deleted", deleted)
                .query((rs, n) -> new AdminUser(Jdbc.uuid(rs, "id"), rs.getString("name"), Jdbc.uuid(rs, "household_id"),
                        rs.getString("household_name"), rs.getString("role"), rs.getBoolean("has_password"),
                        rs.getInt("devices"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "last_used_at"),
                        Jdbc.instant(rs, "deleted_at")))
                .list();
    }

    public record RoleRequest(String role) {
    }

    /** The admin may set any role, also leaving a household without an owner. */
    @PatchMapping("/users/{userId}")
    public AdminUser setRole(@PathVariable UUID userId, @RequestBody RoleRequest request) {
        if (request == null || (!HouseholdService.OWNER.equals(request.role()) && !HouseholdService.MEMBER.equals(request.role()))) {
            throw ApiException.badRequest("role must be owner or member");
        }
        int updated = jdbc.sql("UPDATE app_user SET role = :role WHERE id = :id AND deleted_at IS NULL")
                .param("role", request.role()).param("id", userId).update();
        if (updated == 0) {
            throw new NotFoundException("user " + userId + " not found");
        }
        return users(null, false).stream().filter(u -> u.userId().equals(userId)).findFirst()
                .orElseThrow(() -> new NotFoundException("user " + userId + " not found"));
    }

    // --- recipes ---

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminRecipe(UUID recipeId, UUID householdId, String householdName, String title, String sourceSummary,
                              String thumbnailBlob, boolean needsReview, boolean deleted, int revisions,
                              Instant createdAt, Instant updatedAt) {
    }

    /** Recipes, last changed first; {@code q}: part of the title (case and accents ignored). */
    @GetMapping("/recipes")
    public List<AdminRecipe> recipes(@RequestParam(required = false) UUID householdId,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "false") boolean deleted) {
        return jdbc.sql("""
                        SELECT r.id, r.household_id, h.name AS household_name, r.title, r.source_summary, r.thumbnail_blob,
                               coalesce(v.needs_review, false) AS needs_review, r.created_at, r.updated_at,
                               r.deleted_at IS NOT NULL OR r.merged_into IS NOT NULL AS deleted,
                               (SELECT count(*) FROM recipe_revision x WHERE x.recipe_id = r.id) AS revisions
                        FROM recipe r JOIN household h ON h.id = r.household_id
                             LEFT JOIN recipe_revision v ON v.id = r.head_revision_id
                        WHERE (CAST(:household AS uuid) IS NULL OR r.household_id = :household)
                          AND (:deleted OR (r.deleted_at IS NULL AND r.merged_into IS NULL))
                          AND (CAST(:q AS text) IS NULL OR r.title_norm LIKE '%%' || rd_normalize(:q) || '%%')
                        ORDER BY r.updated_at DESC
                        LIMIT %d""".formatted(MAX_ROWS))
                .param("household", householdId).param("deleted", deleted)
                .param("q", q == null || q.isBlank() ? null : q.strip())
                .query((rs, n) -> new AdminRecipe(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "household_id"),
                        rs.getString("household_name"), rs.getString("title"), rs.getString("source_summary"),
                        rs.getString("thumbnail_blob"), rs.getBoolean("needs_review"), rs.getBoolean("deleted"),
                        rs.getInt("revisions"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at")))
                .list();
    }

    public record AdminRevision(UUID revisionId, boolean head, String origin, boolean needsReview, UUID captureId,
                                String userName, Instant createdAt) {
    }

    public record AdminRecipeDetail(AdminRecipe recipe, JsonNode content, List<String> tags, List<String> urls,
                                    List<AdminRevision> revisions) {
    }

    /** One recipe with its head content, tags, URLs and revisions (also a deleted one). */
    @GetMapping("/recipes/{recipeId}")
    public AdminRecipeDetail recipe(@PathVariable UUID recipeId) {
        AdminRecipe recipe = jdbc.sql("""
                        SELECT r.id, r.household_id, h.name AS household_name, r.title, r.source_summary, r.thumbnail_blob,
                               coalesce(v.needs_review, false) AS needs_review, r.created_at, r.updated_at,
                               r.deleted_at IS NOT NULL OR r.merged_into IS NOT NULL AS deleted,
                               (SELECT count(*) FROM recipe_revision x WHERE x.recipe_id = r.id) AS revisions,
                               v.content::text AS content
                        FROM recipe r JOIN household h ON h.id = r.household_id
                             LEFT JOIN recipe_revision v ON v.id = r.head_revision_id
                        WHERE r.id = :id""")
                .param("id", recipeId)
                .query((rs, n) -> new AdminRecipe(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "household_id"),
                        rs.getString("household_name"), rs.getString("title"), rs.getString("source_summary"),
                        rs.getString("thumbnail_blob"), rs.getBoolean("needs_review"), rs.getBoolean("deleted"),
                        rs.getInt("revisions"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at")))
                .optional()
                .orElseThrow(() -> new NotFoundException("recipe " + recipeId + " not found"));
        String content = jdbc.sql("""
                        SELECT v.content::text FROM recipe r JOIN recipe_revision v ON v.id = r.head_revision_id
                        WHERE r.id = :id""").param("id", recipeId).query(String.class).optional().orElse(null);
        List<String> tags = jdbc.sql("SELECT category || ':' || tag FROM recipe_tag WHERE recipe_id = :id ORDER BY 1")
                .param("id", recipeId).query(String.class).list();
        List<String> urls = jdbc.sql("SELECT value FROM recipe_key WHERE recipe_id = :id ORDER BY created_at")
                .param("id", recipeId).query(String.class).list();
        List<AdminRevision> revisions = jdbc.sql("""
                        SELECT v.id, v.id = r.head_revision_id AS head, v.origin, v.needs_review, v.capture_id,
                               u.name AS user_name, v.created_at
                        FROM recipe_revision v JOIN recipe r ON r.id = v.recipe_id LEFT JOIN app_user u ON u.id = v.user_id
                        WHERE v.recipe_id = :id ORDER BY v.created_at DESC, v.id DESC""")
                .param("id", recipeId)
                .query((rs, n) -> new AdminRevision(Jdbc.uuid(rs, "id"), rs.getBoolean("head"), rs.getString("origin"),
                        rs.getBoolean("needs_review"), Jdbc.uuid(rs, "capture_id"), rs.getString("user_name"),
                        Jdbc.instant(rs, "created_at")))
                .list();
        return new AdminRecipeDetail(recipe, content == null ? null : jsonMapper.readTree(content), tags, urls, revisions);
    }

    // --- captures ---

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminCapture(UUID captureId, UUID householdId, String householdName, String userName,
                               String deviceName, String sourceType, String sourceUrl, String title, String status,
                               String reason, String message, int attachments, BigDecimal llmCostUsd,
                               Instant receivedAt, List<UUID> recipeIds) {
    }

    /** Captures, newest first; {@code status}: comma-separated capture states. */
    @GetMapping("/captures")
    public List<AdminCapture> captures(@RequestParam(required = false) UUID householdId,
                                       @RequestParam(required = false) String status) {
        List<String> statuses = status == null || status.isBlank() ? null
                : List.of(status.split(",")).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        return jdbc.sql("""
                        SELECT c.id, c.household_id, h.name AS household_name, u.name AS user_name, d.device_name,
                               c.source_type, c.source_url, nullif(btrim(c.body->'recipe'->>'title'), '') AS title,
                               s.status, s.reason, s.message, c.received_at,
                               (SELECT count(*) FROM capture_attachment a WHERE a.capture_id = c.id) AS attachments,
                               (SELECT coalesce(sum(r.cost_usd), 0) FROM processing_run r WHERE r.capture_id = c.id) AS cost,
                               ARRAY(SELECT DISTINCT cr.applied_recipe_id::text FROM capture_resolution cr
                                     WHERE cr.capture_id = c.id AND cr.applied_recipe_id IS NOT NULL) AS recipes
                        FROM capture c JOIN capture_state s ON s.capture_id = c.id
                             JOIN household h ON h.id = c.household_id
                             JOIN app_user u ON u.id = c.user_id JOIN device_token d ON d.id = c.device_id
                        WHERE (CAST(:household AS uuid) IS NULL OR c.household_id = :household)
                          AND (CAST(:statuses AS text[]) IS NULL OR s.status = ANY (CAST(:statuses AS text[])))
                        ORDER BY c.received_at DESC
                        LIMIT %d""".formatted(MAX_ROWS))
                .param("household", householdId)
                .param("statuses", statuses == null ? null : "{" + String.join(",", statuses) + "}")
                .query((rs, n) -> new AdminCapture(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "household_id"),
                        rs.getString("household_name"), rs.getString("user_name"), rs.getString("device_name"),
                        rs.getString("source_type"), rs.getString("source_url"), rs.getString("title"),
                        rs.getString("status"), rs.getString("reason"), rs.getString("message"),
                        rs.getInt("attachments"), rs.getBigDecimal("cost"), Jdbc.instant(rs, "received_at"),
                        uuids(rs, "recipes")))
                .list();
    }

    private static List<UUID> uuids(ResultSet rs, String column) throws SQLException {
        return List.of((String[]) rs.getArray(column).getArray()).stream().map(UUID::fromString).toList();
    }

    // --- blobs ---

    /** Any blob (thumbnails and attachments in the dashboard). */
    @GetMapping("/blobs/{sha256}")
    public ResponseEntity<InputStreamResource> blob(@PathVariable String sha256) throws IOException {
        if (!BlobStore.isValidSha256(sha256)) {
            throw new NotFoundException("blob " + sha256 + " not found");
        }
        String mediaType = jdbc.sql("""
                        SELECT coalesce((SELECT a.media_type FROM capture_attachment a WHERE a.blob_sha256 = b.sha256 LIMIT 1),
                                        (SELECT w.media_type FROM web_image w WHERE w.blob_sha256 = b.sha256 LIMIT 1),
                                        CASE WHEN EXISTS (SELECT 1 FROM capture c WHERE c.body_sha256 = b.sha256)
                                             THEN 'application/json' END,
                                        'image/jpeg')
                        FROM blob b WHERE b.sha256 = :sha256""")
                .param("sha256", sha256).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("blob " + sha256 + " not found"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .contentType(MediaType.parseMediaType(mediaType))
                .body(new InputStreamResource(blobs.open(sha256)));
    }
}
