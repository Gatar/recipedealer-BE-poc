package com.gatar.recipedealer.lock;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.recipe.RecipeRepository;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

/** 5-minute edit leases per recipe, compared against the database clock. */
@Service
public class LockService {

    public static final String LEASE = "5 minutes";

    private final JdbcClient jdbc;
    private final RecipeRepository recipes;

    public LockService(JdbcClient jdbc, RecipeRepository recipes) {
        this.jdbc = jdbc;
        this.recipes = recipes;
    }

    public record Lease(UUID lockId, Instant expiresAt) {
    }

    public record Holder(String lockedBy, Instant expiresAt) {
    }

    /** Thrown when another session holds an active lease; rendered as 409 {@code {lockedBy, expiresAt}}. */
    public static class LockedException extends RuntimeException {

        private final Holder holder;

        public LockedException(Holder holder) {
            super("recipe is being edited by " + holder.lockedBy());
            this.holder = holder;
        }

        public Holder holder() {
            return holder;
        }
    }

    @Transactional
    public Lease acquire(UUID recipeId, DevicePrincipal device) {
        var head = recipes.lockHead(recipeId).orElseThrow(() -> new NotFoundException("recipe " + recipeId + " not found"));
        if (head.deleted() || head.mergedInto() != null) {
            throw new ApiException(HttpStatus.GONE, "recipe_gone", "recipe " + recipeId + " was deleted or merged");
        }
        Optional<Lease> lease = jdbc.sql("""
                        INSERT INTO recipe_lock (recipe_id, lock_id, user_id, device_id, acquired_at, expires_at)
                        VALUES (:recipeId, :lockId, :userId, :deviceId, now(), now() + CAST(:lease AS interval))
                        ON CONFLICT (recipe_id) DO UPDATE
                            SET lock_id = EXCLUDED.lock_id, user_id = EXCLUDED.user_id, device_id = EXCLUDED.device_id,
                                acquired_at = EXCLUDED.acquired_at, expires_at = EXCLUDED.expires_at
                            WHERE recipe_lock.expires_at < now()
                        RETURNING lock_id, expires_at""")
                .param("recipeId", recipeId)
                .param("lockId", Ids.newId())
                .param("userId", device.userId())
                .param("deviceId", device.deviceId())
                .param("lease", LEASE)
                .query((rs, n) -> new Lease(Jdbc.uuid(rs, "lock_id"), Jdbc.instant(rs, "expires_at")))
                .optional();
        return lease.orElseThrow(() -> new LockedException(holder(recipeId)));
    }

    /** Extends an active lease by 5 minutes. */
    @Transactional
    public Lease heartbeat(UUID recipeId, UUID lockId) {
        return jdbc.sql("""
                        UPDATE recipe_lock SET expires_at = now() + CAST(:lease AS interval)
                        WHERE recipe_id = :recipeId AND lock_id = :lockId AND expires_at >= now()
                        RETURNING lock_id, expires_at""")
                .param("recipeId", recipeId)
                .param("lockId", lockId)
                .param("lease", LEASE)
                .query((rs, n) -> new Lease(Jdbc.uuid(rs, "lock_id"), Jdbc.instant(rs, "expires_at")))
                .optional()
                .orElseThrow(() -> lockLost(recipeId));
    }

    /** Ends the editing session; idempotent. */
    @Transactional
    public void release(UUID recipeId, UUID lockId) {
        jdbc.sql("DELETE FROM recipe_lock WHERE recipe_id = :recipeId AND lock_id = :lockId")
                .param("recipeId", recipeId)
                .param("lockId", lockId)
                .update();
    }

    /**
     * Checks inside the caller's transaction that {@code lockId} is the active lease and consumes it (the lock row is
     * deleted when the transaction commits).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(UUID recipeId, UUID lockId) {
        if (lockId == null) {
            throw ApiException.conflict("lock_required", "the Lock-Id header is required");
        }
        int deleted = jdbc.sql("DELETE FROM recipe_lock WHERE recipe_id = :recipeId AND lock_id = :lockId AND expires_at >= now()")
                .param("recipeId", recipeId)
                .param("lockId", lockId)
                .update();
        if (deleted == 0) {
            throw lockLost(recipeId);
        }
    }

    /** The active lease of a recipe, for display. */
    public Optional<Holder> activeHolder(UUID recipeId) {
        return jdbc.sql("""
                        SELECT u.name || ' (' || d.device_name || ')' AS locked_by, l.expires_at
                        FROM recipe_lock l JOIN app_user u ON u.id = l.user_id JOIN device_token d ON d.id = l.device_id
                        WHERE l.recipe_id = :recipeId AND l.expires_at >= now()""")
                .param("recipeId", recipeId)
                .query((rs, n) -> new Holder(rs.getString("locked_by"), Jdbc.instant(rs, "expires_at")))
                .optional();
    }

    private Holder holder(UUID recipeId) {
        return activeHolder(recipeId).orElseGet(() -> new Holder("another session", Instant.now()));
    }

    private RuntimeException lockLost(UUID recipeId) {
        return activeHolder(recipeId)
                .<RuntimeException>map(LockedException::new)
                .orElseGet(() -> ApiException.conflict("lock_expired", "the edit lock is missing or has expired"));
    }
}
