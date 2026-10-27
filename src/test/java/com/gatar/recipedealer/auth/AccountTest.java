package com.gatar.recipedealer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestcontainersConfiguration;
import com.gatar.recipedealer.web.ApiException;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class AccountTest {

    private static final String CODE = TestcontainersConfiguration.REGISTRATION_CODE;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeviceTokenService tokens;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    LoginRateLimiter rateLimiter;

    @Autowired
    AdminAuthRateLimiter adminRateLimiter;

    String name;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        adminRateLimiter.reset();
        name = "User " + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void resetRateLimit() {
        rateLimiter.reset();
    }

    @Test
    void registrationReturnsAWorkingTokenAndStoresOnlyAPasswordHash() {
        var result = register(name, "sekret", "Pixel 9", CODE);
        assertThat(result).hasStatus(201);
        String body = TestAuth.body(result);
        assertThat(JsonPath.<String>read(body, "$.userName")).isEqualTo(name);
        assertThat(JsonPath.<String>read(body, "$.token")).startsWith("rd_");
        assertThat(health(JsonPath.read(body, "$.token"))).hasStatusOk();

        String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE id = :id")
                .param("id", UUID.fromString(JsonPath.read(body, "$.userId"))).query(String.class).single();
        assertThat(hash).doesNotContain("sekret");
        assertThat(passwords.matches("sekret", hash)).isTrue();
    }

    @Test
    void userNamesAreUniqueIgnoringCase() {
        assertThat(register(name, "a", "phone", CODE)).hasStatus(201);
        var again = register(name.toUpperCase(), "b", "tablet", CODE);
        assertThat(again).hasStatus(409);
        assertThat(JsonPath.<String>read(TestAuth.body(again), "$.error")).isEqualTo("user_exists");
    }

    @Test
    void registrationNeedsTheHouseholdCode() {
        assertThat(error(register(name, "a", "phone", "wrong"))).isEqualTo("invalid_registration_code");
        assertThat(error(register(name, "a", "phone", null))).isEqualTo("invalid_registration_code");
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE name = :name").param("name", name)
                .query(Long.class).single()).isZero();
    }

    @Test
    void registrationIsDisabledWithoutACode() {
        var service = new AccountService(jdbc, tokens, passwords, rateLimiter, new RegistrationProperties(null, 10));
        assertThatThrownBy(() -> service.register(name, "a", "phone", ""))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("registration_disabled"));
    }

    @Test
    void registrationStopsAtTheUserLimit() {
        int users = jdbc.sql("SELECT count(*) FROM app_user").query(Integer.class).single();
        var full = new AccountService(jdbc, tokens, passwords, rateLimiter, new RegistrationProperties(CODE, users));
        assertThatThrownBy(() -> full.register(name, "a", "phone", CODE))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("user_limit_reached"));

        var oneLeft = new AccountService(jdbc, tokens, passwords, rateLimiter, new RegistrationProperties(CODE, users + 1));
        assertThat(oneLeft.register(name, "a", "phone", CODE).userName()).isEqualTo(name);
    }

    @Test
    void loginIssuesANewDeviceTokenEachTime() {
        String first = TestAuth.body(register(name, "hasło", "phone", CODE));

        var login = login(name.toLowerCase(), "hasło", "tablet");
        assertThat(login).hasStatus(201);
        String body = TestAuth.body(login);
        assertThat(JsonPath.<String>read(body, "$.userName")).isEqualTo(name);
        assertThat(JsonPath.<String>read(body, "$.userId")).isEqualTo(JsonPath.read(first, "$.userId"));
        assertThat(JsonPath.<String>read(body, "$.deviceId")).isNotEqualTo(JsonPath.read(first, "$.deviceId"));
        assertThat(health(JsonPath.read(body, "$.token"))).hasStatusOk();
        assertThat(health(JsonPath.read(first, "$.token"))).hasStatusOk();
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSame() {
        register(name, "right", "phone", CODE);
        assertThat(login(name, "wrong", "phone")).hasStatus(401);
        assertThat(error(login(name, "wrong", "phone"))).isEqualTo("invalid_credentials");
        assertThat(error(login(name + " nobody", "right", "phone"))).isEqualTo("invalid_credentials");
        assertThat(error(login(name, "x".repeat(AccountService.MAX_PASSWORD_BYTES + 1), "phone")))
                .isEqualTo("invalid_credentials");
    }

    @Test
    void failedLoginsBlockThatUserForAWhile() {
        register(name, "right", "phone", CODE);
        String other = name + " other";
        register(other, "right", "phone", CODE);
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_KEY; i++) {
            assertThat(login(name, "wrong", "phone")).hasStatus(401);
        }
        assertThat(login(name, "right", "phone")).hasStatus(429);
        assertThat(login(other, "right", "phone")).hasStatus(201);
    }

    @Test
    void passwordsLongerThanBcryptAllowsAreRejected() {
        String body = TestAuth.body(register(name, "x".repeat(AccountService.MAX_PASSWORD_BYTES + 1), "phone", CODE));
        assertThat(JsonPath.<String>read(body, "$.error")).isEqualTo("invalid_request");
        // 36 two-byte characters are exactly 72 bytes.
        assertThat(register(name, "ż".repeat(36), "phone", CODE)).hasStatus(201);
    }

    @Test
    void anEnrolledUserSetsAPasswordWithItsTokenThenChangesIt() {
        String token = TestAuth.enroll(mvc, name, "kitchen");
        assertThat(login(name, "first", "phone")).hasStatus(401);

        assertThat(setPassword(token, "{\"password\":\"first\"}")).hasStatus(204);
        assertThat(login(name, "first", "phone")).hasStatus(201);

        assertThat(error(setPassword(token, "{\"password\":\"second\"}"))).isEqualTo("invalid_credentials");
        assertThat(error(setPassword(token, "{\"password\":\"second\",\"currentPassword\":\"nope\"}")))
                .isEqualTo("invalid_credentials");
        assertThat(setPassword(token, "{\"password\":\"second\",\"currentPassword\":\"first\"}")).hasStatus(204);
        assertThat(login(name, "first", "phone")).hasStatus(401);
        assertThat(login(name, "second", "phone")).hasStatus(201);
    }

    @Test
    void settingAPasswordNeedsAToken() {
        assertThat(mvc.put().uri("/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"x\"}")).hasStatus(401);
    }

    @Test
    void missingFieldsAreInvalid() {
        assertThat(mvc.post().uri("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\":\"" + name + "\"}")).hasStatus(400);
    }

    private MvcTestResult register(String userName, String password, String deviceName, String code) {
        String codeJson = code == null ? "" : ",\"registrationCode\":\"" + code + "\"";
        return mvc.post().uri("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\":\"%s\",\"password\":\"%s\",\"deviceName\":\"%s\"%s}"
                        .formatted(userName, password, deviceName, codeJson))
                .exchange();
    }

    private MvcTestResult login(String userName, String password, String deviceName) {
        return mvc.post().uri("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\":\"%s\",\"password\":\"%s\",\"deviceName\":\"%s\"}"
                        .formatted(userName, password, deviceName))
                .exchange();
    }

    private MvcTestResult setPassword(String token, String json) {
        return mvc.put().uri("/v1/me/password").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult health(String token) {
        return mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private static String error(MvcTestResult result) {
        return JsonPath.read(TestAuth.body(result), "$.error");
    }
}
