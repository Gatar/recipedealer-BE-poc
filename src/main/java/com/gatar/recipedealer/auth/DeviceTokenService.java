package com.gatar.recipedealer.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.common.Hashing;
import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.common.Jdbc;

@Service
public class DeviceTokenService {

    static final String TOKEN_PREFIX = "rd_";

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public DeviceTokenService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Enrollment(String token, UUID userId, UUID deviceId) {
    }

    public record Device(UUID deviceId, String deviceName, UUID userId, String userName,
                         Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
    }

    /** Creates the user if needed and issues a token for its device (see {@link #issue}). */
    @Transactional
    public Enrollment enroll(String userName, String deviceName) {
        UUID userId = jdbc.sql("""
                        INSERT INTO app_user (id, name) VALUES (:id, :name)
                        ON CONFLICT (lower(name)) WHERE deleted_at IS NULL DO UPDATE SET name = app_user.name
                        RETURNING id""")
                .param("id", Ids.newId())
                .param("name", userName.strip())
                .query(UUID.class).single();
        return issue(userId, deviceName);
    }

    /**
     * A new token for the user's device with this name (case ignored): the device is created, or keeps its id and
     * gets a new token, which revokes the previous one and lifts a revocation. The plain token is returned only here.
     */
    @Transactional
    public Enrollment issue(UUID userId, String deviceName) {
        String token = newToken();
        UUID deviceId = jdbc.sql("""
                        INSERT INTO device_token (id, user_id, device_name, token_hash) VALUES (:id, :userId, :name, :hash)
                        ON CONFLICT (user_id, lower(device_name)) DO UPDATE
                            SET device_name = EXCLUDED.device_name, token_hash = EXCLUDED.token_hash, revoked_at = NULL
                        RETURNING id""")
                .param("id", Ids.newId())
                .param("userId", userId)
                .param("name", deviceName.strip())
                .param("hash", Hashing.sha256(token))
                .query(UUID.class)
                .single();
        return new Enrollment(token, userId, deviceId);
    }

    /**
     * Deletes a user: revokes all its devices and drops its edit locks; the row stays for the history that references
     * it, and the name is free for a new user. Returns false if the user does not exist; deleting twice is a no-op.
     */
    @Transactional
    public boolean deleteUser(UUID userId) {
        int updated = jdbc.sql("UPDATE app_user SET deleted_at = coalesce(deleted_at, now()), password_hash = NULL WHERE id = :id")
                .param("id", userId)
                .update();
        if (updated == 0) {
            return false;
        }
        jdbc.sql("UPDATE device_token SET revoked_at = coalesce(revoked_at, now()) WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM recipe_lock WHERE user_id = :id").param("id", userId).update();
        return true;
    }

    /** Looks up an active token and records its use (at most once per minute per device). */
    public Optional<DevicePrincipal> authenticate(String token) {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        var principal = jdbc.sql("""
                        SELECT d.id AS device_id, d.device_name, u.id AS user_id, u.name AS user_name
                        FROM device_token d JOIN app_user u ON u.id = d.user_id
                        WHERE d.token_hash = :hash AND d.revoked_at IS NULL AND u.deleted_at IS NULL""")
                .param("hash", Hashing.sha256(token))
                .query((rs, n) -> new DevicePrincipal(Jdbc.uuid(rs, "user_id"), rs.getString("user_name"),
                        Jdbc.uuid(rs, "device_id"), rs.getString("device_name")))
                .optional();
        principal.ifPresent(p -> jdbc.sql("""
                        UPDATE device_token SET last_used_at = now()
                        WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')""")
                .param("id", p.deviceId())
                .update());
        return principal;
    }

    public List<Device> devices() {
        return jdbc.sql("""
                        SELECT d.id, d.device_name, d.created_at, d.last_used_at, d.revoked_at, u.id AS user_id, u.name AS user_name
                        FROM device_token d JOIN app_user u ON u.id = d.user_id
                        WHERE u.deleted_at IS NULL
                        ORDER BY u.name, d.created_at""")
                .query((rs, n) -> new Device(Jdbc.uuid(rs, "id"), rs.getString("device_name"), Jdbc.uuid(rs, "user_id"),
                        rs.getString("user_name"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "last_used_at"),
                        Jdbc.instant(rs, "revoked_at")))
                .list();
    }

    /** Revokes a device token; returns false if the device does not exist. Revoking twice is a no-op. */
    public boolean revoke(UUID deviceId) {
        int updated = jdbc.sql("UPDATE device_token SET revoked_at = coalesce(revoked_at, now()) WHERE id = :id")
                .param("id", deviceId)
                .update();
        return updated > 0;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
