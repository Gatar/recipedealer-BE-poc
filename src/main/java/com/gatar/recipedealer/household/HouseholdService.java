package com.gatar.recipedealer.household;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gatar.recipedealer.auth.AccountService;
import com.gatar.recipedealer.auth.AccountService.Session;
import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.auth.DeviceTokenService;
import com.gatar.recipedealer.auth.LoginRateLimiter;
import com.gatar.recipedealer.auth.RegistrationProperties;
import com.gatar.recipedealer.common.Hashing;
import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

import io.github.bucket4j.Bucket;

/**
 * Self-service households: anyone creates one (and becomes its owner), owners invite members with single-use codes,
 * manage members and devices; every user can delete its own account. Deleting is soft: users and the household get
 * {@code deleted_at}, devices are revoked, the data stays.
 */
@Service
public class HouseholdService {

    public static final String OWNER = "owner";
    public static final String MEMBER = "member";
    static final String INVITATION_KEY = "invitation";
    /** No 0/O, 1/I/L: codes are read aloud and typed by hand. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final DeviceTokenService tokens;
    private final LoginRateLimiter rateLimiter;
    private final HouseholdProperties properties;
    private final RegistrationProperties registration;
    private final SecureRandom random = new SecureRandom();
    private final Bucket creations;

    public HouseholdService(JdbcClient jdbc, AccountService accounts, DeviceTokenService tokens,
                            LoginRateLimiter rateLimiter, HouseholdProperties properties,
                            RegistrationProperties registration) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.registration = registration;
        this.creations = Bucket.builder()
                .addLimit(limit -> limit.capacity(properties.maxNewPerHour())
                        .refillGreedy(properties.maxNewPerHour(), Duration.ofHours(1)))
                .build();
    }

    /** {@code recipeLimit} null = no limit. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HouseholdView(UUID householdId, String name, Integer recipeLimit, int recipeCount, int userLimit,
                                int userCount, Instant createdAt) {
    }

    public record MeView(UUID userId, String userName, String role, boolean hasPassword, UUID deviceId,
                         String deviceName, HouseholdView household) {
    }

    public record DeviceView(UUID deviceId, String deviceName, Instant createdAt, Instant lastUsedAt, boolean current) {
    }

    public record MemberView(UUID userId, String userName, String role, Instant createdAt, List<DeviceView> devices) {
    }

    /** {@code code} is shown only when the invitation is created. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvitationView(UUID invitationId, String code, String createdBy, Instant createdAt, Instant expiresAt) {
    }

    // --- registration ---

    /** A new household with its owner and the owner's first device. */
    @Transactional
    public Session createHousehold(String householdName, String userName, String password, String deviceName) {
        String name = name(householdName);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('household_registration'))").query().singleRow();
        long households = jdbc.sql("SELECT count(*) FROM household WHERE deleted_at IS NULL").query(Long.class).single();
        if (households >= properties.maxHouseholds()) {
            throw ApiException.conflict("household_limit_reached",
                    "the limit of " + properties.maxHouseholds() + " households is reached");
        }
        if (!creations.tryConsume(1)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests",
                    "too many new households, try again later");
        }
        UUID householdId = Ids.newId();
        jdbc.sql("INSERT INTO household (id, name, recipe_limit) VALUES (:id, :name, :limit)")
                .param("id", householdId).param("name", name).param("limit", properties.defaultRecipeLimit()).update();
        return accounts.createUser(householdId, OWNER, userName, password, deviceName);
    }

    /** A new member with an invitation code; the code then stops working. */
    @Transactional
    public Session join(String invitationCode, String userName, String password, String deviceName) {
        rateLimiter.check(INVITATION_KEY);
        record Invitation(UUID id, UUID householdId) {
        }
        var invitation = jdbc.sql("""
                        SELECT i.id, i.household_id FROM household_invitation i JOIN household h ON h.id = i.household_id
                        WHERE i.code_hash = :hash AND i.used_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > now()
                          AND h.deleted_at IS NULL
                        FOR UPDATE OF i""")
                .param("hash", Hashing.sha256(normalizeCode(invitationCode)))
                .query((rs, n) -> new Invitation(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "household_id")))
                .optional();
        if (invitation.isEmpty()) {
            rateLimiter.failure(INVITATION_KEY);
            throw new ApiException(HttpStatus.FORBIDDEN, "invalid_invitation_code",
                    "the invitation code is wrong, used or expired");
        }
        Session session = accounts.createUser(invitation.get().householdId(), MEMBER, userName, password, deviceName);
        jdbc.sql("UPDATE household_invitation SET used_at = now(), used_by = :user WHERE id = :id")
                .param("user", session.userId()).param("id", invitation.get().id()).update();
        return session;
    }

    // --- the caller and its household ---

    @Transactional(readOnly = true)
    public MeView me(DevicePrincipal caller) {
        record User(String role, boolean hasPassword) {
        }
        User user = jdbc.sql("SELECT role, password_hash IS NOT NULL AS has_password FROM app_user WHERE id = :id")
                .param("id", caller.userId())
                .query((rs, n) -> new User(rs.getString("role"), rs.getBoolean("has_password"))).single();
        return new MeView(caller.userId(), caller.userName(), user.role(), user.hasPassword(), caller.deviceId(),
                caller.deviceName(), household(caller.householdId()));
    }

    @Transactional(readOnly = true)
    public HouseholdView household(UUID householdId) {
        return jdbc.sql("""
                        SELECT h.id, h.name, h.recipe_limit, h.created_at,
                               (SELECT count(*) FROM recipe r WHERE r.household_id = h.id
                                  AND r.deleted_at IS NULL AND r.merged_into IS NULL) AS recipes,
                               (SELECT count(*) FROM app_user u WHERE u.household_id = h.id AND u.deleted_at IS NULL) AS users
                        FROM household h WHERE h.id = :id""")
                .param("id", householdId)
                .query((rs, n) -> new HouseholdView(Jdbc.uuid(rs, "id"), rs.getString("name"),
                        (Integer) rs.getObject("recipe_limit"), rs.getInt("recipes"), registration.maxUsers(),
                        rs.getInt("users"), Jdbc.instant(rs, "created_at")))
                .single();
    }

    @Transactional
    public HouseholdView rename(DevicePrincipal caller, String householdName) {
        requireOwner(caller);
        jdbc.sql("UPDATE household SET name = :name WHERE id = :id")
                .param("name", name(householdName)).param("id", caller.householdId()).update();
        return household(caller.householdId());
    }

    /**
     * Deletes the caller's account. The last owner cannot leave other members without an owner (409
     * {@code last_owner}); the last user of a household takes the household along (not the default household).
     */
    @Transactional
    public void deleteMe(DevicePrincipal caller) {
        lockHousehold(caller.householdId());
        if (OWNER.equals(role(caller.userId())) && owners(caller.householdId()) == 1 && users(caller.householdId()) > 1) {
            throw ApiException.conflict("last_owner", "make another member an owner first");
        }
        tokens.deleteUser(caller.userId());
        if (users(caller.householdId()) == 0 && !Households.DEFAULT_ID.equals(caller.householdId())) {
            jdbc.sql("UPDATE household SET deleted_at = now() WHERE id = :id").param("id", caller.householdId()).update();
            revokeInvitations(caller.householdId());
        }
    }

    /** Deletes the household with all its users (owner only; never the default household). */
    @Transactional
    public void deleteHousehold(DevicePrincipal caller) {
        requireOwner(caller);
        if (Households.DEFAULT_ID.equals(caller.householdId())) {
            throw ApiException.conflict("default_household", "the default household cannot be deleted");
        }
        lockHousehold(caller.householdId());
        for (UUID user : jdbc.sql("SELECT id FROM app_user WHERE household_id = :id AND deleted_at IS NULL")
                .param("id", caller.householdId()).query(UUID.class).list()) {
            tokens.deleteUser(user);
        }
        revokeInvitations(caller.householdId());
        jdbc.sql("UPDATE household SET deleted_at = now() WHERE id = :id").param("id", caller.householdId()).update();
    }

    // --- members and devices ---

    @Transactional(readOnly = true)
    public List<MemberView> members(DevicePrincipal caller) {
        Map<UUID, MemberView> members = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id, name, role, created_at FROM app_user
                        WHERE household_id = :household AND deleted_at IS NULL ORDER BY created_at, name""")
                .param("household", caller.householdId())
                .query(rs -> {
                    UUID id = Jdbc.uuid(rs, "id");
                    members.put(id, new MemberView(id, rs.getString("name"), rs.getString("role"),
                            Jdbc.instant(rs, "created_at"), new ArrayList<>()));
                });
        jdbc.sql("""
                        SELECT d.id, d.user_id, d.device_name, d.created_at, d.last_used_at
                        FROM device_token d JOIN app_user u ON u.id = d.user_id
                        WHERE u.household_id = :household AND u.deleted_at IS NULL AND d.revoked_at IS NULL
                        ORDER BY d.created_at""")
                .param("household", caller.householdId())
                .query(rs -> {
                    UUID id = Jdbc.uuid(rs, "id");
                    members.get(Jdbc.uuid(rs, "user_id")).devices().add(new DeviceView(id, rs.getString("device_name"),
                            Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "last_used_at"), id.equals(caller.deviceId())));
                });
        return List.copyOf(members.values());
    }

    /** Owner only; the last owner cannot become a member (409 {@code last_owner}). */
    @Transactional
    public List<MemberView> setRole(DevicePrincipal caller, UUID userId, String role) {
        requireOwner(caller);
        if (!OWNER.equals(role) && !MEMBER.equals(role)) {
            throw ApiException.badRequest("role must be owner or member");
        }
        lockHousehold(caller.householdId());
        requireMember(caller.householdId(), userId);
        if (MEMBER.equals(role) && OWNER.equals(role(userId)) && owners(caller.householdId()) == 1) {
            throw ApiException.conflict("last_owner", "the household needs at least one owner");
        }
        jdbc.sql("UPDATE app_user SET role = :role WHERE id = :id").param("role", role).param("id", userId).update();
        return members(caller);
    }

    /** Owner only; a user removes itself with {@code DELETE /v1/me}. */
    @Transactional
    public void removeMember(DevicePrincipal caller, UUID userId) {
        requireOwner(caller);
        if (userId.equals(caller.userId())) {
            throw ApiException.badRequest("use DELETE /v1/me to delete your own account");
        }
        requireMember(caller.householdId(), userId);
        tokens.deleteUser(userId);
    }

    /** An owner revokes any device of the household, a member only its own. */
    @Transactional
    public void revokeDevice(DevicePrincipal caller, UUID deviceId) {
        UUID owner = jdbc.sql("""
                        SELECT d.user_id FROM device_token d JOIN app_user u ON u.id = d.user_id
                        WHERE d.id = :id AND u.household_id = :household AND u.deleted_at IS NULL""")
                .param("id", deviceId).param("household", caller.householdId())
                .query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("device " + deviceId + " not found"));
        if (!owner.equals(caller.userId())) {
            requireOwner(caller);
        }
        tokens.revoke(deviceId);
    }

    // --- invitations ---

    @Transactional
    public InvitationView invite(DevicePrincipal caller) {
        requireOwner(caller);
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        UUID id = Ids.newId();
        Instant expiresAt = jdbc.sql("""
                        INSERT INTO household_invitation (id, household_id, code_hash, created_by, expires_at)
                        VALUES (:id, :household, :hash, :user, now() + make_interval(secs => :seconds))
                        RETURNING expires_at""")
                .param("id", id).param("household", caller.householdId())
                .param("hash", Hashing.sha256(code.toString())).param("user", caller.userId())
                .param("seconds", (double) properties.invitationTtl().toSeconds())
                .query((rs, n) -> Jdbc.instant(rs, "expires_at")).single();
        String shown = code.substring(0, CODE_LENGTH / 2) + "-" + code.substring(CODE_LENGTH / 2);
        return new InvitationView(id, shown, caller.userName(), Instant.now(), expiresAt);
    }

    /** Invitations that still work, newest first (owner only). */
    @Transactional(readOnly = true)
    public List<InvitationView> invitations(DevicePrincipal caller) {
        requireOwner(caller);
        return jdbc.sql("""
                        SELECT i.id, u.name, i.created_at, i.expires_at
                        FROM household_invitation i JOIN app_user u ON u.id = i.created_by
                        WHERE i.household_id = :household AND i.used_at IS NULL AND i.revoked_at IS NULL
                          AND i.expires_at > now()
                        ORDER BY i.created_at DESC""")
                .param("household", caller.householdId())
                .query((rs, n) -> new InvitationView(Jdbc.uuid(rs, "id"), null, rs.getString("name"),
                        Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at")))
                .list();
    }

    @Transactional
    public void revokeInvitation(DevicePrincipal caller, UUID invitationId) {
        requireOwner(caller);
        int updated = jdbc.sql("""
                        UPDATE household_invitation SET revoked_at = coalesce(revoked_at, now())
                        WHERE id = :id AND household_id = :household""")
                .param("id", invitationId).param("household", caller.householdId()).update();
        if (updated == 0) {
            throw new NotFoundException("invitation " + invitationId + " not found");
        }
    }

    // --- helpers ---

    /** 403 {@code owner_required} unless the caller is an owner of its household. */
    private void requireOwner(DevicePrincipal caller) {
        if (!OWNER.equals(role(caller.userId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner_required", "only an owner of the household can do this");
        }
    }

    private void requireMember(UUID householdId, UUID userId) {
        boolean member = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id AND household_id = :household
                                       AND deleted_at IS NULL)""")
                .param("id", userId).param("household", householdId).query(Boolean.class).single();
        if (!member) {
            throw new NotFoundException("user " + userId + " not found");
        }
    }

    private String role(UUID userId) {
        return jdbc.sql("SELECT role FROM app_user WHERE id = :id").param("id", userId).query(String.class).single();
    }

    private long owners(UUID householdId) {
        return jdbc.sql("SELECT count(*) FROM app_user WHERE household_id = :id AND deleted_at IS NULL AND role = 'owner'")
                .param("id", householdId).query(Long.class).single();
    }

    private long users(UUID householdId) {
        return jdbc.sql("SELECT count(*) FROM app_user WHERE household_id = :id AND deleted_at IS NULL")
                .param("id", householdId).query(Long.class).single();
    }

    /** Serializes membership changes of one household (role checks would race otherwise). */
    private void lockHousehold(UUID householdId) {
        jdbc.sql("SELECT id FROM household WHERE id = :id FOR UPDATE").param("id", householdId).query(UUID.class).single();
    }

    private void revokeInvitations(UUID householdId) {
        jdbc.sql("UPDATE household_invitation SET revoked_at = now() WHERE household_id = :id AND revoked_at IS NULL AND used_at IS NULL")
                .param("id", householdId).update();
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String name(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > 100) {
            throw ApiException.badRequest("householdName must be 1-100 characters");
        }
        return stripped;
    }
}
