package com.gatar.recipedealer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.common.Hashing;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class EnrollmentAndTokenAuthTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AdminAuthRateLimiter rateLimiter;

    @BeforeEach
    void resetRateLimit() {
        rateLimiter.reset();
    }

    private String json(MvcTestResult result) {
        return TestAuth.body(result);
    }

    @Test
    void enrollmentReturnsTokenOnceAndStoresOnlyItsHash() {
        var result = TestAuth.enrollRequest(mvc, "Ania", "Pixel 9");
        assertThat(result).hasStatus(201);
        String body = json(result);
        String token = JsonPath.read(body, "$.token");
        UUID deviceId = UUID.fromString(JsonPath.read(body, "$.deviceId"));

        assertThat(token).startsWith("rd_").hasSize(3 + 43);
        byte[] storedHash = jdbc.sql("SELECT token_hash FROM device_token WHERE id = :id").param("id", deviceId)
                .query(byte[].class).single();
        assertThat(storedHash).isEqualTo(Hashing.sha256(token));
        assertThat(jdbc.sql("SELECT count(*) FROM device_token WHERE encode(token_hash, 'escape') LIKE '%rd_%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void enrollingSameUserNameReusesUser() {
        String first = json(TestAuth.enrollRequest(mvc, "Tablet", "kitchen"));
        String second = json(TestAuth.enrollRequest(mvc, "tablet", "kitchen-2"));

        assertThat((String) JsonPath.read(second, "$.userId")).isEqualTo(JsonPath.read(first, "$.userId"));
        assertThat((String) JsonPath.read(second, "$.deviceId")).isNotEqualTo(JsonPath.read(first, "$.deviceId"));
    }

    @Test
    void enrollingTheSameDeviceAgainReplacesItsToken() {
        String user = "Device " + UUID.randomUUID().toString().substring(0, 8);
        String first = json(TestAuth.enrollRequest(mvc, user, "kitchen"));
        String second = json(TestAuth.enrollRequest(mvc, user, "Kitchen"));

        assertThat((String) JsonPath.read(second, "$.deviceId")).isEqualTo(JsonPath.read(first, "$.deviceId"));
        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(JsonPath.read(first, "$.token"))))
                .hasStatus(401);
        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(JsonPath.read(second, "$.token"))))
                .hasStatusOk();
    }

    @Test
    void enrollmentValidatesBody() {
        assertThat(TestAuth.enrollRequest(mvc, " ", "phone")).hasStatus(400)
                .bodyJson().extractingPath("$.error").isEqualTo("invalid_request");
    }

    @Test
    void adminEndpointsRejectWrongOrMissingCredentials() {
        assertThat(mvc.get().uri("/v1/admin/devices")).hasStatus(401);
        assertThat(mvc.get().uri("/v1/admin/devices").header(HttpHeaders.AUTHORIZATION, TestAuth.basic("admin", "wrong")))
                .hasStatus(401);
    }

    @Test
    void deviceTokenDoesNotGrantAdminAccess() {
        String token = TestAuth.enroll(mvc, "Bartek", "phone");
        assertThat(mvc.get().uri("/v1/admin/devices").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatus(401);
    }

    @Test
    void unknownOrMalformedTokensAreRejected() {
        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer("rd_unknown")))
                .hasStatus(401);
        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer("garbage")))
                .hasStatus(401);
        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()))
                .hasStatus(401);
    }

    @Test
    void tokenUseUpdatesLastUsedAtAndDevicesAreListed() {
        String body = json(TestAuth.enrollRequest(mvc, "Ola", "Galaxy"));
        String token = JsonPath.read(body, "$.token");
        String deviceId = JsonPath.read(body, "$.deviceId");

        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatusOk();

        var devices = mvc.get().uri("/v1/admin/devices").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()).exchange();
        assertThat(devices).hasStatusOk();
        String list = json(devices);
        assertThat((List<String>) JsonPath.read(list, "$[?(@.deviceId == '" + deviceId + "')].lastUsedAt")).hasSize(1)
                .doesNotContainNull();
        assertThat((List<String>) JsonPath.read(list, "$[?(@.deviceId == '" + deviceId + "')].userName"))
                .containsExactly("Ola");
    }

    @Test
    void revokedTokenIsRejected() {
        String body = json(TestAuth.enrollRequest(mvc, "Kasia", "phone"));
        String token = JsonPath.read(body, "$.token");
        String deviceId = JsonPath.read(body, "$.deviceId");
        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatusOk();

        assertThat(mvc.delete().uri("/v1/admin/devices/{id}", deviceId).header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()))
                .hasStatus(204);

        assertThat(mvc.get().uri("/v1/health").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatus(401);
        assertThat(mvc.delete().uri("/v1/admin/devices/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()))
                .hasStatus(404);
    }

    @Test
    void fiveFailedAdminLoginsBlockAllAdminRequests() {
        for (int i = 0; i < AdminAuthRateLimiter.MAX_FAILURES_PER_HOUR; i++) {
            assertThat(mvc.get().uri("/v1/admin/devices").header(HttpHeaders.AUTHORIZATION, TestAuth.basic("admin", "wrong-" + i)))
                    .hasStatus(401);
        }

        var blocked = mvc.get().uri("/v1/admin/devices").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()).exchange();
        assertThat(blocked).hasStatus(429).hasHeader(HttpHeaders.RETRY_AFTER, blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(Long.parseLong(blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
    }

    @Test
    void successfulAdminLoginsDoNotCountTowardsTheLimit() {
        for (int i = 0; i < AdminAuthRateLimiter.MAX_FAILURES_PER_HOUR + 2; i++) {
            assertThat(mvc.get().uri("/v1/admin/devices").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())).hasStatusOk();
        }
    }
}
