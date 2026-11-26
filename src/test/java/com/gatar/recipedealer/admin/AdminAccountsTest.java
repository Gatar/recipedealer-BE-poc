package com.gatar.recipedealer.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.auth.LoginRateLimiter;
import com.gatar.recipedealer.household.Households;
import com.jayway.jsonpath.JsonPath;

/** The admin's account actions: user name and password, device name, households and their invitations. */
@IntegrationTest
class AdminAccountsTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LoginRateLimiter rateLimiter;

    String sfx;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        sfx = UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcTestResult admin(String method, String uri, String body, Object... vars) {
        var request = switch (method) {
            case "POST" -> mvc.post().uri(uri, vars);
            case "PATCH" -> mvc.patch().uri(uri, vars);
            case "DELETE" -> mvc.delete().uri(uri, vars);
            default -> mvc.get().uri(uri, vars);
        };
        request = request.header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private MvcTestResult login(String userName, String password, String deviceName) {
        return mvc.post().uri("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\": \"%s\", \"password\": \"%s\", \"deviceName\": \"%s\"}"
                        .formatted(userName, password, deviceName))
                .exchange();
    }

    /** With an e-mail address: the first user of an admin's household becomes its owner. */
    private MvcTestResult join(String code, String userName) {
        return mvc.post().uri("/v1/auth/join").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"invitationCode": "%s", "userName": "%s", "password": "x", "deviceName": "Pixel",
                         "email": "%s@example.org"}""".formatted(code, userName, userName.toLowerCase()))
                .exchange();
    }

    private String userId(String userName) {
        List<String> ids = JsonPath.read(TestAuth.body(admin("GET", "/v1/admin/users", null)),
                "$[?(@.userName == '" + userName + "')].userId");
        return ids.getFirst();
    }

    @Test
    void theAdminRenamesAUserAndSetsAPassword() {
        String token = TestAuth.enroll(mvc, "Old-" + sfx, "phone");
        TestAuth.enroll(mvc, "Taken-" + sfx, "phone");
        String id = userId("Old-" + sfx);

        var taken = admin("PATCH", "/v1/admin/users/{id}", "{\"userName\": \"taken-" + sfx + "\"}", id);
        assertThat(taken).hasStatus(409);
        assertThat(TestAuth.body(taken)).contains("user_exists");

        var changed = admin("PATCH", "/v1/admin/users/{id}",
                "{\"userName\": \"New-" + sfx + "\", \"password\": \"nowe-hasło\"}", id);
        assertThat(changed).hasStatusOk();
        assertThat(JsonPath.<String>read(TestAuth.body(changed), "$.userName")).isEqualTo("New-" + sfx);
        assertThat(JsonPath.<Boolean>read(TestAuth.body(changed), "$.hasPassword")).isTrue();
        // the enrolled device keeps working; the new name and password log in
        assertThat(mvc.get().uri("/v1/me").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))).hasStatusOk();
        assertThat(login("New-" + sfx, "nowe-hasło", "tablet")).hasStatus(201);
        assertThat(login("Old-" + sfx, "nowe-hasło", "tablet")).hasStatus(401);

        assertThat(admin("PATCH", "/v1/admin/users/{id}", "{\"password\": \"\"}", id)).hasStatus(400);
        assertThat(admin("PATCH", "/v1/admin/users/{id}", "{}", id)).hasStatus(400);
        assertThat(admin("PATCH", "/v1/admin/users/{id}", "{\"userName\": \"x\"}", UUID.randomUUID())).hasStatus(404);
    }

    @Test
    void theAdminSetsAnEmailAddressAndOwnersKeepOne() {
        TestAuth.enroll(mvc, "Mail-" + sfx, "phone");
        String id = userId("Mail-" + sfx);
        // enrolled users are members of the default household without an e-mail address
        assertThat(error(admin("PATCH", "/v1/admin/users/{id}", "{\"role\": \"owner\"}", id)))
                .isEqualTo("member_email_required");
        assertThat(error(admin("PATCH", "/v1/admin/users/{id}", "{\"email\": \"zły\"}", id))).isEqualTo("invalid_request");

        var changed = admin("PATCH", "/v1/admin/users/{id}",
                "{\"email\": \"mail-" + sfx + "@example.org\", \"role\": \"owner\"}", id);
        assertThat(changed).hasStatusOk();
        assertThat(JsonPath.<String>read(TestAuth.body(changed), "$.email")).isEqualTo("mail-" + sfx + "@example.org");
        assertThat(JsonPath.<String>read(TestAuth.body(changed), "$.role")).isEqualTo("owner");
        assertThat(error(admin("PATCH", "/v1/admin/users/{id}", "{\"email\": \"\"}", id)))
                .isEqualTo("member_email_required");

        TestAuth.enroll(mvc, "Mail2-" + sfx, "phone");
        assertThat(error(admin("PATCH", "/v1/admin/users/{id}", "{\"email\": \"MAIL-" + sfx + "@example.org\"}",
                userId("Mail2-" + sfx)))).isEqualTo("email_exists");
        assertThat(admin("PATCH", "/v1/admin/users/{id}", "{\"role\": \"member\", \"email\": \"\"}", id)).hasStatusOk();
    }

    private static String error(MvcTestResult result) {
        return JsonPath.read(TestAuth.body(result), "$.error");
    }

    @Test
    void theAdminRenamesADevice() {
        String token = TestAuth.enroll(mvc, "Dev-" + sfx, "phone");
        TestAuth.enroll(mvc, "Dev-" + sfx, "tablet");
        String deviceId = JsonPath.read(TestAuth.body(mvc.get().uri("/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange()), "$.deviceId");

        var taken = admin("PATCH", "/v1/admin/devices/{id}", "{\"deviceName\": \"TABLET\"}", deviceId);
        assertThat(taken).hasStatus(409);
        assertThat(TestAuth.body(taken)).contains("device_exists");
        var renamed = admin("PATCH", "/v1/admin/devices/{id}", "{\"deviceName\": \"Pixel 9\"}", deviceId);
        assertThat(renamed).hasStatusOk();
        assertThat(JsonPath.<String>read(TestAuth.body(renamed), "$.deviceName")).isEqualTo("Pixel 9");
        assertThat(JsonPath.<String>read(TestAuth.body(mvc.get().uri("/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange()), "$.deviceName")).isEqualTo("Pixel 9");
        assertThat(admin("PATCH", "/v1/admin/devices/{id}", "{\"deviceName\": \"\"}", deviceId)).hasStatus(400);
        assertThat(admin("PATCH", "/v1/admin/devices/{id}", "{\"deviceName\": \"x\"}", UUID.randomUUID())).hasStatus(404);
    }

    @Test
    void anAdminHouseholdGetsItsOwnerThroughAnInvitation() {
        var created = admin("POST", "/v1/admin/households", "{\"name\": \"Znajomi " + sfx + "\"}");
        assertThat(created).hasStatus(201);
        String household = JsonPath.read(TestAuth.body(created), "$.householdId");
        assertThat(JsonPath.<Integer>read(TestAuth.body(created), "$.recipeLimit")).isEqualTo(40);
        assertThat(JsonPath.<Double>read(TestAuth.body(created), "$.llmBudgetUsd")).isEqualTo(1.0);
        assertThat(JsonPath.<Integer>read(TestAuth.body(created), "$.users")).isZero();

        var invitation = admin("POST", "/v1/admin/households/{id}/invitations", null, household);
        assertThat(invitation).hasStatus(201);
        String code = JsonPath.read(TestAuth.body(invitation), "$.code");
        assertThat(JsonPath.<String>read(TestAuth.body(admin("GET", "/v1/admin/households/{id}/invitations", null, household)),
                "$[0].createdBy")).isEqualTo("admin");

        var first = join(code, "First-" + sfx);
        assertThat(first).hasStatus(201);
        String firstToken = JsonPath.read(TestAuth.body(first), "$.token");
        assertThat(JsonPath.<String>read(TestAuth.body(mvc.get().uri("/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(firstToken)).exchange()), "$.role")).isEqualTo("owner");

        String second = JsonPath.read(TestAuth.body(admin("POST", "/v1/admin/households/{id}/invitations", null, household)),
                "$.code");
        String secondToken = JsonPath.read(TestAuth.body(join(second, "Second-" + sfx)), "$.token");
        assertThat(JsonPath.<String>read(TestAuth.body(mvc.get().uri("/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(secondToken)).exchange()), "$.role")).isEqualTo("member");

        // the admin deletes the household with its users
        assertThat(admin("DELETE", "/v1/admin/households/{id}", null, household)).hasStatus(204);
        assertThat(mvc.get().uri("/v1/me").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(firstToken))).hasStatus(401);
        assertThat(admin("POST", "/v1/admin/households/{id}/invitations", null, household)).hasStatus(404);
        assertThat(admin("DELETE", "/v1/admin/households/{id}", null, household)).hasStatus(204);
    }

    @Test
    void anAdminHouseholdMayHaveNoLimitsAndInvitationsAreRevoked() {
        var created = admin("POST", "/v1/admin/households",
                "{\"name\": \"Bez limitów " + sfx + "\", \"recipeLimit\": null, \"llmBudgetUsd\": null}");
        String household = JsonPath.read(TestAuth.body(created), "$.householdId");
        assertThat(JsonPath.<Object>read(TestAuth.body(created), "$.recipeLimit")).isNull();
        assertThat(JsonPath.<Object>read(TestAuth.body(created), "$.llmBudgetUsd")).isNull();

        var invitation = TestAuth.body(admin("POST", "/v1/admin/households/{id}/invitations", null, household));
        assertThat(admin("DELETE", "/v1/admin/households/{id}/invitations/{inv}", null, household,
                JsonPath.read(invitation, "$.invitationId"))).hasStatus(204);
        assertThat(join(JsonPath.read(invitation, "$.code"), "Late-" + sfx)).hasStatus(403);
        assertThat(admin("DELETE", "/v1/admin/households/{id}/invitations/{inv}", null, Households.DEFAULT_ID,
                JsonPath.read(invitation, "$.invitationId"))).hasStatus(404);

        assertThat(admin("POST", "/v1/admin/households", "{\"recipeLimit\": 3}")).hasStatus(400);
        assertThat(admin("DELETE", "/v1/admin/households/{id}", null, Households.DEFAULT_ID)).hasStatus(409);
        assertThat(admin("DELETE", "/v1/admin/households/{id}", null, UUID.randomUUID())).hasStatus(404);
        assertThat(admin("GET", "/v1/admin/households/{id}/invitations", null, UUID.randomUUID())).hasStatus(404);
    }
}
