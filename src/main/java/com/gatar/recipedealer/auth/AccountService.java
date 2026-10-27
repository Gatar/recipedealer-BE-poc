package com.gatar.recipedealer.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.web.ApiException;

/** Self-service accounts: registration with the household code, password login, password change. */
@Service
public class AccountService {

    /** bcrypt uses only the first 72 bytes; longer passwords are rejected instead of silently truncated. */
    public static final int MAX_PASSWORD_BYTES = 72;
    static final String REGISTRATION_KEY = "registration";

    private final JdbcClient jdbc;
    private final DeviceTokenService tokens;
    private final PasswordEncoder passwords;
    private final LoginRateLimiter rateLimiter;
    private final RegistrationProperties registration;
    /** Checked when the user does not exist, so the answer takes as long as a wrong password. */
    private final String dummyHash;

    public AccountService(JdbcClient jdbc, DeviceTokenService tokens, PasswordEncoder passwords,
                          LoginRateLimiter rateLimiter, RegistrationProperties registration) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.registration = registration;
        this.dummyHash = passwords.encode("not-a-password");
    }

    /** A new device token of a user; {@code userName} as stored. */
    public record Session(String token, UUID userId, UUID deviceId, String userName) {
    }

    private record Account(UUID id, String name, String passwordHash) {
    }

    @Transactional
    public Session register(String userName, String password, String deviceName, String registrationCode) {
        if (!registration.enabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "registration_disabled", "registration is disabled");
        }
        rateLimiter.check(REGISTRATION_KEY);
        if (registrationCode == null || !MessageDigest.isEqual(registrationCode.getBytes(StandardCharsets.UTF_8),
                registration.code().getBytes(StandardCharsets.UTF_8))) {
            rateLimiter.failure(REGISTRATION_KEY);
            throw new ApiException(HttpStatus.FORBIDDEN, "invalid_registration_code", "invalid registration code");
        }
        String hash = passwords.encode(checkedPassword(password));
        // Serializes registrations, so concurrent ones cannot exceed the limit.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('app_user_registration'))").query().singleRow();
        long users = jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
        if (users >= registration.maxUsers()) {
            throw ApiException.conflict("user_limit_reached", "the limit of " + registration.maxUsers() + " users is reached");
        }
        UUID userId = jdbc.sql("""
                        INSERT INTO app_user (id, name, password_hash) VALUES (:id, :name, :hash)
                        ON CONFLICT (lower(name)) DO NOTHING
                        RETURNING id""")
                .param("id", Ids.newId())
                .param("name", userName.strip())
                .param("hash", hash)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> ApiException.conflict("user_exists", "user " + userName.strip() + " already exists"));
        return session(tokens.issue(userId, deviceName), userName.strip());
    }

    /** A new device token for the user; every login is a new device. */
    public Session login(String userName, String password, String deviceName) {
        String key = "user:" + userName.strip().toLowerCase(Locale.ROOT);
        rateLimiter.check(key);
        Optional<Account> account = account("lower(name) = lower(:name)", "name", userName.strip());
        String hash = account.map(Account::passwordHash).orElse(null);
        boolean valid = matches(password, hash != null ? hash : dummyHash) && hash != null;
        if (!valid) {
            rateLimiter.failure(key);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "wrong user name or password");
        }
        return session(tokens.issue(account.get().id(), deviceName), account.get().name());
    }

    /** Sets the caller's password; {@code currentPassword} is required once the user has one. */
    public void setPassword(DevicePrincipal caller, String password, String currentPassword) {
        String hash = passwords.encode(checkedPassword(password));
        String current = account("id = :id", "id", caller.userId()).map(Account::passwordHash).orElse(null);
        if (current != null) {
            String key = "user:" + caller.userName().toLowerCase(Locale.ROOT);
            rateLimiter.check(key);
            if (!matches(currentPassword, current)) {
                rateLimiter.failure(key);
                throw new ApiException(HttpStatus.FORBIDDEN, "invalid_credentials", "the current password is wrong");
            }
        }
        jdbc.sql("UPDATE app_user SET password_hash = :hash WHERE id = :id")
                .param("hash", hash)
                .param("id", caller.userId())
                .update();
    }

    private Optional<Account> account(String condition, String param, Object value) {
        return jdbc.sql("SELECT id, name, password_hash FROM app_user WHERE " + condition)
                .param(param, value)
                .query((rs, n) -> new Account(Jdbc.uuid(rs, "id"), rs.getString("name"), rs.getString("password_hash")))
                .optional();
    }

    /** bcrypt rejects passwords over 72 bytes, so those never match. */
    private boolean matches(String password, String hash) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES
                && passwords.matches(password, hash);
    }

    private static String checkedPassword(String password) {
        if (password == null || password.isEmpty()) {
            throw ApiException.badRequest("password must not be empty");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.badRequest("password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        return password;
    }

    private static Session session(DeviceTokenService.Enrollment enrollment, String userName) {
        return new Session(enrollment.token(), enrollment.userId(), enrollment.deviceId(), userName);
    }
}
