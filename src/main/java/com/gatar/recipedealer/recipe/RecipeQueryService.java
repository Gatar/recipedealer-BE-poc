package com.gatar.recipedealer.recipe;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class RecipeQueryService {

    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 500;
    public static final int SEARCH_LIMIT = 30;
    /** Lower than the pg_trgm default (0.6) so that small typos still match. */
    public static final double WORD_SIMILARITY_THRESHOLD = 0.45;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public RecipeQueryService(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecipeView(UUID recipeId, UUID headRevisionId, String origin, boolean needsReview, JsonNode recipe,
                             String sourceSummary, String thumbnailBlob, List<String> urls, Instant createdAt,
                             Instant updatedAt, LockInfo lock) {
    }

    /** The active edit lease, if someone is editing. */
    public record LockInfo(String lockedBy, Instant expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevisionView(UUID revisionId, UUID parentId, boolean head, String origin, boolean needsReview,
                               UUID runId, UUID captureId, String userName, String contentHash, Instant createdAt,
                               JsonNode recipe) {
    }

    /** A changed recipe with its head content, or a tombstone ({@code deleted} or {@code mergedInto}, no content). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SyncItem(UUID recipeId, Instant updatedAt, boolean deleted, UUID mergedInto, UUID headRevisionId,
                           Boolean needsReview, JsonNode recipe, String sourceSummary, String thumbnailBlob) {
    }

    /** {@code nextCursor} resumes after the last item (keep it for the next sync); {@code hasMore} asks for another page now. */
    public record SyncPage(List<SyncItem> items, String nextCursor, boolean hasMore) {
    }

    public record SearchHit(UUID recipeId, String title, double score, String sourceSummary, String thumbnailBlob,
                            Instant updatedAt) {
    }

    public RecipeView recipe(UUID recipeId) {
        var state = jdbc.sql("SELECT deleted_at IS NOT NULL AS deleted, merged_into FROM recipe WHERE id = :id")
                .param("id", recipeId)
                .query((rs, n) -> new Object[] {rs.getBoolean("deleted"), Jdbc.uuid(rs, "merged_into")})
                .optional()
                .orElseThrow(() -> new NotFoundException("recipe " + recipeId + " not found"));
        if (state[1] != null) {
            throw new ApiException(HttpStatus.GONE, "recipe_merged", "recipe was merged into " + state[1]);
        }
        if ((boolean) state[0]) {
            throw new ApiException(HttpStatus.GONE, "recipe_deleted", "recipe " + recipeId + " was deleted");
        }
        return jdbc.sql("""
                        SELECT r.id, r.head_revision_id, h.origin, h.needs_review, h.content, r.source_summary,
                               r.thumbnail_blob, r.created_at, r.updated_at,
                               ARRAY(SELECT k.value FROM recipe_key k WHERE k.recipe_id = r.id AND k.kind = 'CANONICAL_URL'
                                     ORDER BY k.created_at) AS urls,
                               u.name || ' (' || d.device_name || ')' AS locked_by, l.expires_at AS lock_expires_at
                        FROM recipe r JOIN recipe_revision h ON h.id = r.head_revision_id
                             LEFT JOIN recipe_lock l ON l.recipe_id = r.id AND l.expires_at >= now()
                             LEFT JOIN app_user u ON u.id = l.user_id
                             LEFT JOIN device_token d ON d.id = l.device_id
                        WHERE r.id = :id""")
                .param("id", recipeId)
                .query((rs, n) -> new RecipeView(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "head_revision_id"),
                        rs.getString("origin"), rs.getBoolean("needs_review"), json(rs, "content"),
                        rs.getString("source_summary"), rs.getString("thumbnail_blob"),
                        List.of((String[]) rs.getArray("urls").getArray()), Jdbc.instant(rs, "created_at"),
                        Jdbc.instant(rs, "updated_at"), rs.getString("locked_by") == null ? null
                                : new LockInfo(rs.getString("locked_by"), Jdbc.instant(rs, "lock_expires_at"))))
                .single();
    }

    /** All revisions, newest first; non-head revisions after the head are proposals. */
    public List<RevisionView> revisions(UUID recipeId) {
        if (jdbc.sql("SELECT count(*) FROM recipe WHERE id = :id").param("id", recipeId).query(Long.class).single() == 0) {
            throw new NotFoundException("recipe " + recipeId + " not found");
        }
        return jdbc.sql("""
                        SELECT v.id, v.parent_id, v.id = r.head_revision_id AS head, v.origin, v.needs_review, v.run_id,
                               v.capture_id, u.name AS user_name, v.content_hash, v.created_at, v.content
                        FROM recipe_revision v JOIN recipe r ON r.id = v.recipe_id LEFT JOIN app_user u ON u.id = v.user_id
                        WHERE v.recipe_id = :id
                        ORDER BY v.created_at DESC, v.id DESC""")
                .param("id", recipeId)
                .query((rs, n) -> new RevisionView(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "parent_id"), rs.getBoolean("head"),
                        rs.getString("origin"), rs.getBoolean("needs_review"), Jdbc.uuid(rs, "run_id"),
                        Jdbc.uuid(rs, "capture_id"), rs.getString("user_name"), rs.getString("content_hash"),
                        Jdbc.instant(rs, "created_at"), json(rs, "content")))
                .list();
    }

    /**
     * Sync feed ordered by {@code (updated_at, id)}. Start with {@code updatedSince} (inclusive, or nothing for a full
     * sync) and continue with {@code nextCursor} until it is null; keep the last cursor for the next sync.
     */
    public SyncPage sync(Instant updatedSince, String cursor, Integer limit) {
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : Math.clamp(limit, 1, MAX_PAGE_SIZE);
        Instant afterTime;
        UUID afterId;
        if (cursor != null && !cursor.isBlank()) {
            var decoded = decodeCursor(cursor);
            afterTime = decoded.time();
            afterId = decoded.id();
        } else {
            afterTime = updatedSince == null ? Instant.EPOCH : updatedSince.minusNanos(1000);
            afterId = new UUID(-1L, -1L);
        }
        List<SyncItem> items = jdbc.sql("""
                        SELECT r.id, r.updated_at, r.deleted_at IS NOT NULL AS deleted, r.merged_into, r.head_revision_id,
                               h.needs_review, h.content, r.source_summary, r.thumbnail_blob
                        FROM recipe r JOIN recipe_revision h ON h.id = r.head_revision_id
                        WHERE (r.updated_at, r.id) > (:afterTime, :afterId)
                        ORDER BY r.updated_at, r.id
                        LIMIT :limit""")
                .param("afterTime", java.time.OffsetDateTime.ofInstant(afterTime, java.time.ZoneOffset.UTC))
                .param("afterId", afterId)
                .param("limit", pageSize + 1)
                .query((rs, n) -> syncItem(rs))
                .list();
        boolean more = items.size() > pageSize;
        List<SyncItem> page = more ? items.subList(0, pageSize) : items;
        String next = page.isEmpty() ? cursor : encodeCursor(page.getLast().updatedAt(), page.getLast().recipeId());
        return new SyncPage(page, next, more);
    }

    /** Title and ingredient search tolerant to missing diacritics and small typos. */
    public List<SearchHit> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        jdbc.sql("SELECT set_config('pg_trgm.word_similarity_threshold', :threshold, true)")
                .param("threshold", Double.toString(WORD_SIMILARITY_THRESHOLD))
                .query().singleRow();
        return jdbc.sql("""
                        SELECT r.id, r.title, word_similarity(q.t, r.search_text) AS score, r.source_summary,
                               r.thumbnail_blob, r.updated_at
                        FROM recipe r, (SELECT rd_normalize(:q) AS t) q
                        WHERE q.t <% r.search_text AND r.deleted_at IS NULL AND r.merged_into IS NULL
                        ORDER BY score DESC, word_similarity(q.t, r.title_norm) DESC, r.updated_at DESC
                        LIMIT :limit""")
                .param("q", query)
                .param("limit", SEARCH_LIMIT)
                .query((rs, n) -> new SearchHit(Jdbc.uuid(rs, "id"), rs.getString("title"),
                        Math.round(rs.getDouble("score") * 1000) / 1000.0, rs.getString("source_summary"),
                        rs.getString("thumbnail_blob"), Jdbc.instant(rs, "updated_at")))
                .list();
    }

    private SyncItem syncItem(ResultSet rs) throws SQLException {
        boolean deleted = rs.getBoolean("deleted");
        UUID mergedInto = Jdbc.uuid(rs, "merged_into");
        boolean tombstone = deleted || mergedInto != null;
        return new SyncItem(Jdbc.uuid(rs, "id"), Jdbc.instant(rs, "updated_at"), deleted, mergedInto,
                tombstone ? null : Jdbc.uuid(rs, "head_revision_id"), tombstone ? null : rs.getBoolean("needs_review"),
                tombstone ? null : json(rs, "content"), tombstone ? null : rs.getString("source_summary"),
                tombstone ? null : rs.getString("thumbnail_blob"));
    }

    private JsonNode json(ResultSet rs, String column) throws SQLException {
        return jsonMapper.readTree(rs.getString(column));
    }

    private record Cursor(Instant time, UUID id) {
    }

    static String encodeCursor(Instant time, UUID id) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, time);
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static Cursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            int colon = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, colon));
            return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), UUID.fromString(raw.substring(colon + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid cursor");
        }
    }
}
