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

    /** Creates the user if needed and a new device token. The plain token is returned only here. */
    @Transactional
    public Enrollment enroll(String userName, String deviceName) {
        UUID userId = jdbc.sql("""
                        INSERT INTO app_user (id, name) VALUES (:id, :name)
                        ON CONFLICT (lower(name)) DO UPDATE SET name = app_user.name
                        RETURNING id""")
                .param("id", Ids.newId())
                .param("name", userName.strip())
                .query(UUID.class).single();
        String token = newToken();
        UUID deviceId = Ids.newId();
        jdbc.sql("INSERT INTO device_token (id, user_id, device_name, token_hash) VALUES (:id, :userId, :name, :hash)")
                .param("id", deviceId)
                .param("userId", userId)
                .param("name", deviceName.strip())
                .param("hash", Hashing.sha256(token))
                .update();
        return new Enrollment(token, userId, deviceId);
    }

    /** Looks up an active token and records its use (at most once per minute per device). */
    public Optional<DevicePrincipal> authenticate(String token) {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        var principal = jdbc.sql("""
                        SELECT d.id AS device_id, d.device_name, u.id AS user_id, u.name AS user_name
                        FROM device_token d JOIN app_user u ON u.id = d.user_id
                        WHERE d.token_hash = :hash AND d.revoked_at IS NULL""")
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
