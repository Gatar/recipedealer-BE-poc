package com.gatar.recipedealer.recipe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.gatar.recipedealer.common.Jdbc;

/** Existing recipes a new capture may belong to: exact canonical URL first, then similar titles. */
@Service
public class CandidateService {

    public static final int LIMIT = 10;

    private final JdbcClient jdbc;

    public CandidateService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum MatchReason { URL, TITLE }

    public record Candidate(UUID recipeId, String title, double score, MatchReason matchReason, String sourceSummary,
                            String thumbnailBlob, Instant updatedAt) {
    }

    private static final String COLUMNS = "r.id, r.title, r.source_summary, r.thumbnail_blob, r.updated_at";

    public List<Candidate> find(String title, String url) {
        List<Candidate> result = new ArrayList<>();
        UrlNormalizer.normalize(url).ifPresent(normalized -> result.addAll(jdbc.sql("""
                        SELECT %s, 1.0 AS score FROM recipe_key k JOIN recipe r ON r.id = k.recipe_id
                        WHERE k.kind = 'CANONICAL_URL' AND k.value = :url AND r.deleted_at IS NULL AND r.merged_into IS NULL"""
                        .formatted(COLUMNS))
                .param("url", normalized)
                .query((rs, n) -> map(rs, MatchReason.URL))
                .list()));
        if (title != null && !title.isBlank()) {
            List<UUID> seen = result.stream().map(Candidate::recipeId).toList();
            jdbc.sql("""
                            SELECT %s, similarity(r.title_norm, q.t) AS score
                            FROM recipe r, (SELECT rd_normalize(:title) AS t) q
                            WHERE r.title_norm %% q.t AND r.deleted_at IS NULL AND r.merged_into IS NULL
                            ORDER BY score DESC, r.updated_at DESC
                            LIMIT :limit""".formatted(COLUMNS))
                    .param("title", title)
                    .param("limit", LIMIT)
                    .query((rs, n) -> map(rs, MatchReason.TITLE))
                    .list().stream()
                    .filter(c -> !seen.contains(c.recipeId()))
                    .forEach(result::add);
        }
        return result.size() > LIMIT ? result.subList(0, LIMIT) : result;
    }

    private static Candidate map(java.sql.ResultSet rs, MatchReason reason) throws java.sql.SQLException {
        return new Candidate(Jdbc.uuid(rs, "id"), rs.getString("title"),
                Math.round(rs.getDouble("score") * 1000) / 1000.0, reason, rs.getString("source_summary"),
                rs.getString("thumbnail_blob"), Jdbc.instant(rs, "updated_at"));
    }
}
