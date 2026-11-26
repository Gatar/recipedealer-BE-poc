package com.gatar.recipedealer.household;

import java.math.BigDecimal;
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
    static final String LOGIN_CODE_KEY = "login_code";
    /** The device name of a code login without one. */
    static final String DEFAULT_DEVICE_NAME = "Urządzenie";
    /** No 0/O, 1/I/L: codes are read aloud and typed by hand. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final DeviceTokenService tokens;
    private final LoginRateLimiter rateLimiter;
    private final HouseholdProperties properties;
    private final RegistrationProperties registration;
    private final LlmBudget budget;
    private final SecureRandom random = new SecureRandom();
    private final Bucket creations;

    public HouseholdService(JdbcClient jdbc, AccountService accounts, DeviceTokenService tokens,
                            LoginRateLimiter rateLimiter, HouseholdProperties properties,
                            RegistrationProperties registration, LlmBudget budget) {
        this.budget = budget;
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

    /** {@code recipeLimit}, {@code llmBudgetUsd} null = no limit; {@code llmSpentUsd}: this calendar month (UTC). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HouseholdView(UUID householdId, String name, Integer recipeLimit, int recipeCount, int userLimit,
                                int userCount, BigDecimal llmBudgetUsd, BigDecimal llmSpentUsd, Instant createdAt) {
    }

    /** {@code email} null when the user has none (an owner without one must set it before owner actions). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MeView(UUID userId, String userName, String role, boolean hasPassword, String email, UUID deviceId,
                         String deviceName, HouseholdView household) {
    }

    public record DeviceView(UUID deviceId, String deviceName, Instant createdAt, Instant lastUsedAt, boolean current) {
    }

    /** {@code hasEmail}: only users with an e-mail address can become owners (the address itself is not shown). */
    public record MemberView(UUID userId, String userName, String role, boolean hasEmail, Instant createdAt,
                             List<DeviceView> devices) {
    }

    /** A login code for an existing account; shown only when it is created. */
    public record LoginCodeView(String code, UUID userId, String userName, UUID deviceId, String deviceName,
                                Instant expiresAt) {
    }

    /** {@code code} is shown only when the invitation is created. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvitationView(UUID invitationId, String code, String createdBy, Instant createdAt, Instant expiresAt) {
    }

    // --- registration ---

    /** A new household with its owner and the owner's first device. */
    @Transactional
    public Session createHousehold(String householdName, String userName, String password, String deviceName,
                                   String email) {
        String name = name(householdName);
        if (AccountService.email(email) == null) {
            throw ApiException.badRequest("email is required: the owner of a household must have an e-mail address");
        }
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
        jdbc.sql("INSERT INTO household (id, name, recipe_limit, llm_budget_usd) VALUES (:id, :name, :limit, :budget)")
                .param("id", householdId).param("name", name).param("limit", properties.defaultRecipeLimit())
                .param("budget", properties.defaultLlmBudgetUsd()).update();
        return accounts.createUser(householdId, OWNER, userName, password, deviceName, email);
    }

    /**
     * A new member with an invitation code; the code then stops working. Joining a household without an owner (one the
     * admin created) makes the new user its owner.
     */
    @Transactional
    public Session join(String invitationCode, String userName, String password, String deviceName, String email) {
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
        lockHousehold(invitation.get().householdId());
        String role = owners(invitation.get().householdId()) == 0 ? OWNER : MEMBER;
        if (OWNER.equals(role) && AccountService.email(email) == null) {
            throw ApiException.conflict("email_required",
                    "the first user of this household becomes its owner and needs an e-mail address");
        }
        Session session = accounts.createUser(invitation.get().householdId(), role, userName, password, deviceName,
                email);
        jdbc.sql("UPDATE household_invitation SET used_at = now(), used_by = :user WHERE id = :id")
                .param("user", session.userId()).param("id", invitation.get().id()).update();
        return session;
    }

    // --- the caller and its household ---

    @Transactional(readOnly = true)
    public MeView me(DevicePrincipal caller) {
        record User(String role, boolean hasPassword, String email) {
        }
        User user = jdbc.sql("SELECT role, password_hash IS NOT NULL AS has_password, email FROM app_user WHERE id = :id")
                .param("id", caller.userId())
                .query((rs, n) -> new User(rs.getString("role"), rs.getBoolean("has_password"), rs.getString("email")))
                .single();
        return new MeView(caller.userId(), caller.userName(), user.role(), user.hasPassword(), user.email(),
                caller.deviceId(), caller.deviceName(), household(caller.householdId()));
    }

    /**
     * Sets or (null / blank) removes the caller's e-mail address; an owner cannot remove it (409
     * {@code email_required}).
     */
    @Transactional
    public MeView updateMe(DevicePrincipal caller, String email) {
        if (AccountService.email(email) == null && OWNER.equals(role(caller.userId()))) {
            throw ApiException.conflict("email_required", "an owner must keep an e-mail address");
        }
        accounts.setEmail(caller.userId(), email);
        return me(caller);
    }

    @Transactional(readOnly = true)
    public HouseholdView household(UUID householdId) {
        return jdbc.sql("""
                        SELECT h.id, h.name, h.recipe_limit, h.llm_budget_usd, h.created_at,
                               (SELECT count(*) FROM recipe r WHERE r.household_id = h.id
                                  AND r.deleted_at IS NULL AND r.merged_into IS NULL) AS recipes,
                               (SELECT count(*) FROM app_user u WHERE u.household_id = h.id AND u.deleted_at IS NULL) AS users
                        FROM household h WHERE h.id = :id""")
                .param("id", householdId)
                .query((rs, n) -> new HouseholdView(Jdbc.uuid(rs, "id"), rs.getString("name"),
                        (Integer) rs.getObject("recipe_limit"), rs.getInt("recipes"), registration.maxUsers(),
                        rs.getInt("users"), rs.getBigDecimal("llm_budget_usd"), budget.spent(householdId),
                        Jdbc.instant(rs, "created_at")))
                .single();
    }

    @Transactional
    public HouseholdView rename(DevicePrincipal caller, String householdName) {
        requireOwnerWithEmail(caller);
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
        deleteHousehold(caller.householdId());
    }

    /**
     * Deletes a household with all its users (also the admin; never the default household); deleting a deleted one
     * again changes nothing. 404 for an unknown household.
     */
    @Transactional
    public void deleteHousehold(UUID householdId) {
        if (Households.DEFAULT_ID.equals(householdId)) {
            throw ApiException.conflict("default_household", "the default household cannot be deleted");
        }
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM household WHERE id = :id)").param("id", householdId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new NotFoundException("household " + householdId + " not found");
        }
        lockHousehold(householdId);
        for (UUID user : jdbc.sql("SELECT id FROM app_user WHERE household_id = :id AND deleted_at IS NULL")
                .param("id", householdId).query(UUID.class).list()) {
            tokens.deleteUser(user);
        }
        revokeInvitations(householdId);
        jdbc.sql("UPDATE household SET deleted_at = coalesce(deleted_at, now()) WHERE id = :id")
                .param("id", householdId).update();
    }

    /**
     * A household the admin creates, without users and beyond the household limit; the first user who joins it with
     * an invitation becomes its owner. {@code recipeLimit}, {@code llmBudgetUsd}: null = no limit.
     */
    @Transactional
    public UUID createHouseholdAsAdmin(String householdName, Integer recipeLimit, BigDecimal llmBudgetUsd) {
        UUID householdId = Ids.newId();
        jdbc.sql("INSERT INTO household (id, name, recipe_limit, llm_budget_usd) VALUES (:id, :name, :limit, :budget)")
                .param("id", householdId).param("name", name(householdName)).param("limit", recipeLimit)
                .param("budget", llmBudgetUsd).update();
        return householdId;
    }

    /** The limits a household created through the app starts with. */
    public HouseholdProperties properties() {
        return properties;
    }

    // --- members and devices ---

    @Transactional(readOnly = true)
    public List<MemberView> members(DevicePrincipal caller) {
        Map<UUID, MemberView> members = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id, name, role, email IS NOT NULL AS has_email, created_at FROM app_user
                        WHERE household_id = :household AND deleted_at IS NULL ORDER BY created_at, name""")
                .param("household", caller.householdId())
                .query(rs -> {
                    UUID id = Jdbc.uuid(rs, "id");
                    members.put(id, new MemberView(id, rs.getString("name"), rs.getString("role"),
                            rs.getBoolean("has_email"), Jdbc.instant(rs, "created_at"), new ArrayList<>()));
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

    /**
     * Owner only; the last owner cannot become a member (409 {@code last_owner}), a user without an e-mail address
     * cannot become an owner (409 {@code member_email_required}).
     */
    @Transactional
    public List<MemberView> setRole(DevicePrincipal caller, UUID userId, String role) {
        requireOwnerWithEmail(caller);
        if (!OWNER.equals(role) && !MEMBER.equals(role)) {
            throw ApiException.badRequest("role must be owner or member");
        }
        lockHousehold(caller.householdId());
        requireMember(caller.householdId(), userId);
        if (MEMBER.equals(role) && OWNER.equals(role(userId)) && owners(caller.householdId()) == 1) {
            throw ApiException.conflict("last_owner", "the household needs at least one owner");
        }
        if (OWNER.equals(role) && accounts.email(userId) == null) {
            throw ApiException.conflict("member_email_required", "only a user with an e-mail address can become an owner");
        }
        jdbc.sql("UPDATE app_user SET role = :role WHERE id = :id").param("role", role).param("id", userId).update();
        return members(caller);
    }

    /** Owner only; a user removes itself with {@code DELETE /v1/me}. */
    @Transactional
    public void removeMember(DevicePrincipal caller, UUID userId) {
        requireOwnerWithEmail(caller);
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
        requireOwnerWithEmail(caller);
        return createInvitation(caller.householdId(), caller.userId(), caller.userName());
    }

    /**
     * A new invitation code of a household; {@code createdBy} null: the admin. 404 for a household that does not
     * exist or was deleted.
     */
    @Transactional
    public InvitationView createInvitation(UUID householdId, UUID createdBy, String createdByName) {
        boolean live = jdbc.sql("SELECT EXISTS (SELECT 1 FROM household WHERE id = :id AND deleted_at IS NULL)")
                .param("id", householdId).query(Boolean.class).single();
        if (!live) {
            throw new NotFoundException("household " + householdId + " not found");
        }
        String code = newCode();
        UUID id = Ids.newId();
        Instant expiresAt = jdbc.sql("""
                        INSERT INTO household_invitation (id, household_id, code_hash, created_by, expires_at)
                        VALUES (:id, :household, :hash, :user, now() + make_interval(secs => :seconds))
                        RETURNING expires_at""")
                .param("id", id).param("household", householdId)
                .param("hash", Hashing.sha256(code)).param("user", createdBy)
                .param("seconds", (double) properties.invitationTtl().toSeconds())
                .query((rs, n) -> Jdbc.instant(rs, "expires_at")).single();
        return new InvitationView(id, shown(code), createdByName, Instant.now(), expiresAt);
    }

    /** Invitations that still work, newest first (owner only). */
    @Transactional(readOnly = true)
    public List<InvitationView> invitations(DevicePrincipal caller) {
        requireOwner(caller);
        return invitations(caller.householdId());
    }

    /** A household's invitations that still work, newest first; {@code createdBy} "admin" for the admin's. */
    @Transactional(readOnly = true)
    public List<InvitationView> invitations(UUID householdId) {
        return jdbc.sql("""
                        SELECT i.id, coalesce(u.name, 'admin') AS name, i.created_at, i.expires_at
                        FROM household_invitation i LEFT JOIN app_user u ON u.id = i.created_by
                        WHERE i.household_id = :household AND i.used_at IS NULL AND i.revoked_at IS NULL
                          AND i.expires_at > now()
                        ORDER BY i.created_at DESC""")
                .param("household", householdId)
                .query((rs, n) -> new InvitationView(Jdbc.uuid(rs, "id"), null, rs.getString("name"),
                        Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at")))
                .list();
    }

    @Transactional
    public void revokeInvitation(DevicePrincipal caller, UUID invitationId) {
        requireOwner(caller);
        revokeInvitation(caller.householdId(), invitationId);
    }

    /** 404 unless the invitation belongs to the household. */
    @Transactional
    public void revokeInvitation(UUID householdId, UUID invitationId) {
        int updated = jdbc.sql("""
                        UPDATE household_invitation SET revoked_at = coalesce(revoked_at, now())
                        WHERE id = :id AND household_id = :household""")
                .param("id", invitationId).param("household", householdId).update();
        if (updated == 0) {
            throw new NotFoundException("invitation " + invitationId + " not found");
        }
    }

    // --- login codes ---

    /**
     * A single-use code that logs another device into an existing account of the household (owner only); codes of
     * that user made before stop working. Works as long as an invitation ({@code invitationTtl}). With
     * {@code deviceId} (a working device of that user, else 404) the code logs in as that device.
     */
    @Transactional
    public LoginCodeView createLoginCode(DevicePrincipal caller, UUID userId, UUID deviceId) {
        requireOwnerWithEmail(caller);
        requireMember(caller.householdId(), userId);
        String deviceName = deviceId == null ? null : jdbc.sql("""
                        SELECT device_name FROM device_token WHERE id = :id AND user_id = :user AND revoked_at IS NULL""")
                .param("id", deviceId).param("user", userId).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("device " + deviceId + " not found"));
        jdbc.sql("UPDATE login_code SET revoked_at = now() WHERE user_id = :user AND used_at IS NULL AND revoked_at IS NULL")
                .param("user", userId).update();
        String code = newCode();
        Instant expiresAt = jdbc.sql("""
                        INSERT INTO login_code (id, user_id, device_id, code_hash, created_by, expires_at)
                        VALUES (:id, :user, :device, :hash, :creator, now() + make_interval(secs => :seconds))
                        RETURNING expires_at""")
                .param("id", Ids.newId()).param("user", userId).param("device", deviceId).param("hash", Hashing.sha256(code))
                .param("creator", caller.userId()).param("seconds", (double) properties.invitationTtl().toSeconds())
                .query((rs, n) -> Jdbc.instant(rs, "expires_at")).single();
        String name = jdbc.sql("SELECT name FROM app_user WHERE id = :id").param("id", userId).query(String.class).single();
        return new LoginCodeView(shown(code), userId, name, deviceId, deviceName, expiresAt);
    }

    /**
     * Logs a device into the account of a login code; the code then stops working. Simpler than a password login (user,
     * 2026-11-26: the owner who made the code vouches for the device). A code made for a device logs in as that device
     * (its name; {@code deviceName} is ignored). Otherwise {@code deviceName} is optional ("Urządzenie" without one).
     * Like a password login, a working device of the user with that name gets the new token instead of a new device
     * being added. 403 {@code invalid_login_code}.
     */
    @Transactional
    public Session loginWithCode(String loginCode, String deviceName) {
        rateLimiter.check(LOGIN_CODE_KEY);
        record Code(UUID id, UUID userId, String deviceName) {
        }
        var code = jdbc.sql("""
                        SELECT c.id, c.user_id, d.device_name FROM login_code c
                        JOIN app_user u ON u.id = c.user_id JOIN household h ON h.id = u.household_id
                        LEFT JOIN device_token d ON d.id = c.device_id
                        WHERE c.code_hash = :hash AND c.used_at IS NULL AND c.revoked_at IS NULL AND c.expires_at > now()
                          AND u.deleted_at IS NULL AND h.deleted_at IS NULL
                        FOR UPDATE OF c""")
                .param("hash", Hashing.sha256(normalizeCode(loginCode)))
                .query((rs, n) -> new Code(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "user_id"), rs.getString("device_name")))
                .optional();
        if (code.isEmpty()) {
            rateLimiter.failure(LOGIN_CODE_KEY);
            throw new ApiException(HttpStatus.FORBIDDEN, "invalid_login_code", "the login code is wrong, used or expired");
        }
        jdbc.sql("UPDATE login_code SET used_at = now() WHERE id = :id").param("id", code.get().id()).update();
        String name = code.get().deviceName() != null ? code.get().deviceName()
                : deviceName == null || deviceName.isBlank() ? DEFAULT_DEVICE_NAME : deviceName;
        return accounts.issue(code.get().userId(), name);
    }

    // --- helpers ---

    /**
     * {@link #requireOwner}, and 409 {@code email_required} while the owner has no e-mail address (owners from before
     * e-mail addresses set one first).
     */
    private void requireOwnerWithEmail(DevicePrincipal caller) {
        requireOwner(caller);
        if (accounts.email(caller.userId()) == null) {
            throw ApiException.conflict("email_required", "set your e-mail address first: owners must have one");
        }
    }

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

    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** {@code ABCD-EFGH}: easier to read aloud and type. */
    private static String shown(String code) {
        return code.substring(0, CODE_LENGTH / 2) + "-" + code.substring(CODE_LENGTH / 2);
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
