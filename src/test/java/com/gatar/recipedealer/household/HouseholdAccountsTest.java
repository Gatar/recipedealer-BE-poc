package com.gatar.recipedealer.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.auth.AccountService;
import com.gatar.recipedealer.auth.DeviceTokenService;
import com.gatar.recipedealer.auth.LoginRateLimiter;
import com.gatar.recipedealer.auth.RegistrationProperties;
import com.gatar.recipedealer.processing.JobWorker;
import com.gatar.recipedealer.web.ApiException;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class HouseholdAccountsTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    AccountService accounts;

    @Autowired
    DeviceTokenService tokens;

    @Autowired
    LoginRateLimiter rateLimiter;

    @Autowired
    LlmBudget budget;

    String sfx;
    String ownerToken;
    String householdId;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        sfx = UUID.randomUUID().toString().substring(0, 8);
        var created = post(null, "/v1/auth/households", """
                {"householdName": "Dom %s", "userName": "Owner-%s", "password": "hasło", "deviceName": "Pixel",
                 "email": "owner-%s@example.org"}"""
                .formatted(sfx, sfx, sfx));
        assertThat(created).hasStatus(201);
        ownerToken = JsonPath.read(TestAuth.body(created), "$.token");
        householdId = JsonPath.read(TestAuth.body(created), "$.householdId");
    }

    @Test
    void aNewHouseholdHasItsOwnerAndTheDefaultRecipeLimit() {
        assertThat(householdId).isNotEqualTo(Households.DEFAULT_ID.toString());
        String me = TestAuth.body(get(ownerToken, "/v1/me"));
        assertThat(JsonPath.<String>read(me, "$.role")).isEqualTo("owner");
        assertThat(JsonPath.<Boolean>read(me, "$.hasPassword")).isTrue();
        assertThat(JsonPath.<String>read(me, "$.deviceName")).isEqualTo("Pixel");
        assertThat(JsonPath.<String>read(me, "$.household.name")).isEqualTo("Dom " + sfx);
        assertThat(JsonPath.<Integer>read(me, "$.household.recipeLimit")).isEqualTo(40);
        assertThat(JsonPath.<Integer>read(me, "$.household.recipeCount")).isZero();
        assertThat(JsonPath.<Integer>read(me, "$.household.userCount")).isEqualTo(1);
        assertThat(JsonPath.<Double>read(me, "$.household.llmBudgetUsd")).isEqualTo(1.0);
        assertThat(JsonPath.<Integer>read(me, "$.household.llmSpentUsd")).isZero();

        var renamed = patch(ownerToken, "/v1/household", "{\"name\": \"Nowa nazwa " + sfx + "\"}");
        assertThat(renamed).hasStatusOk();
        assertThat(JsonPath.<String>read(TestAuth.body(renamed), "$.name")).isEqualTo("Nowa nazwa " + sfx);

        // a taken user name, also of another household
        assertThat(error(post(null, "/v1/auth/households", """
                {"householdName": "Inny", "userName": "owner-%s", "password": "x", "deviceName": "Pixel",
                 "email": "inny-%s@example.org"}"""
                .formatted(sfx, sfx)))).isEqualTo("user_exists");
    }

    @Test
    void anOwnerNeedsAnEmailAddress() {
        assertThat(JsonPath.<String>read(TestAuth.body(get(ownerToken, "/v1/me")), "$.email"))
                .isEqualTo("owner-" + sfx + "@example.org");
        // required for a new household, checked for its shape, unique (case ignored)
        assertThat(error(post(null, "/v1/auth/households", """
                {"householdName": "Bez", "userName": "NoMail-%s", "password": "x", "deviceName": "Pixel"}"""
                .formatted(sfx)))).isEqualTo("invalid_request");
        assertThat(error(post(null, "/v1/auth/households", """
                {"householdName": "Zły", "userName": "BadMail-%s", "password": "x", "deviceName": "Pixel",
                 "email": "nie-mail"}""".formatted(sfx)))).isEqualTo("invalid_request");
        assertThat(error(post(null, "/v1/auth/households", """
                {"householdName": "Ten sam", "userName": "SameMail-%s", "password": "x", "deviceName": "Pixel",
                 "email": "OWNER-%s@example.org"}""".formatted(sfx, sfx)))).isEqualTo("email_exists");

        // an owner cannot remove it, a member can
        assertThat(error(patch(ownerToken, "/v1/me", "{\"email\": \"\"}"))).isEqualTo("email_required");
        var changed = patch(ownerToken, "/v1/me", "{\"email\": \" new-owner-" + sfx + "@example.org \"}");
        assertThat(changed).hasStatusOk();
        assertThat(JsonPath.<String>read(TestAuth.body(changed), "$.email")).isEqualTo("new-owner-" + sfx + "@example.org");
        String memberToken = member("Member-" + sfx);
        assertThat(JsonPath.<Object>read(TestAuth.body(get(memberToken, "/v1/me")), "$.email")).isNull();
        assertThat(error(patch(memberToken, "/v1/me", "{\"email\": \"NEW-owner-" + sfx + "@example.org\"}")))
                .isEqualTo("email_exists");
        assertThat(patch(memberToken, "/v1/me", "{\"email\": \"member-" + sfx + "@example.org\"}")).hasStatusOk();
        assertThat(patch(memberToken, "/v1/me", "{\"email\": null}")).hasStatusOk();
        assertThat(JsonPath.<List<Boolean>>read(TestAuth.body(get(ownerToken, "/v1/household/members")),
                "$[*].hasEmail")).containsExactly(true, false);
        // the old address is free again
        assertThat(post(null, "/v1/auth/households", """
                {"householdName": "Wolny", "userName": "Free-%s", "password": "x", "deviceName": "Pixel",
                 "email": "owner-%s@example.org"}""".formatted(sfx, sfx))).hasStatus(201);
    }

    @Test
    void anOwnerWithoutAnEmailAddressSetsOneBeforeOwnerActions() {
        jdbc.sql("UPDATE app_user SET email = NULL WHERE name = :name").param("name", "Owner-" + sfx).update();
        assertThat(error(post(ownerToken, "/v1/household/invitations", ""))).isEqualTo("email_required");
        assertThat(error(patch(ownerToken, "/v1/household", "{\"name\": \"X\"}"))).isEqualTo("email_required");
        // recipes and the household view keep working
        assertThat(get(ownerToken, "/v1/household/members")).hasStatusOk();
        assertThat(get(ownerToken, "/v1/recipes")).hasStatusOk();
        assertThat(patch(ownerToken, "/v1/me", "{\"email\": \"owner-" + sfx + "@example.org\"}")).hasStatusOk();
        assertThat(post(ownerToken, "/v1/household/invitations", "")).hasStatus(201);
    }

    @Test
    void aLoginCodeLogsAnotherDeviceIntoAnExistingAccount() {
        String memberToken = member("Member-" + sfx);
        String memberId = JsonPath.read(TestAuth.body(get(ownerToken, "/v1/household/members")), "$[1].userId");

        assertThat(error(post(memberToken, "/v1/household/members/" + memberId + "/login-code", "")))
                .isEqualTo("owner_required");
        var first = post(ownerToken, "/v1/household/members/" + memberId + "/login-code", "");
        assertThat(first).hasStatus(201);
        String firstCode = JsonPath.read(TestAuth.body(first), "$.code");
        assertThat(firstCode).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(JsonPath.<String>read(TestAuth.body(first), "$.userName")).isEqualTo("Member-" + sfx);
        // a new code replaces the earlier one
        String code = JsonPath.read(TestAuth.body(post(ownerToken, "/v1/household/members/" + memberId + "/login-code",
                "")), "$.code");
        assertThat(error(loginCode(firstCode, "Tablet"))).isEqualTo("invalid_login_code");

        var session = loginCode(code.toLowerCase().replace("-", ""), "Tablet");
        assertThat(session).hasStatus(201);
        assertThat(JsonPath.<String>read(TestAuth.body(session), "$.householdId")).isEqualTo(householdId);
        String tablet = JsonPath.read(TestAuth.body(session), "$.token");
        String me = TestAuth.body(get(tablet, "/v1/me"));
        assertThat(JsonPath.<String>read(me, "$.userName")).isEqualTo("Member-" + sfx);
        assertThat(JsonPath.<String>read(me, "$.deviceName")).isEqualTo("Tablet");
        assertThat(get(memberToken, "/v1/me")).hasStatusOk();
        // single use
        assertThat(error(loginCode(code, "Laptop"))).isEqualTo("invalid_login_code");

        // the device name is optional
        String noName = JsonPath.read(TestAuth.body(post(null, "/v1/auth/login-code", "{\"loginCode\": \"%s\"}".formatted(
                JsonPath.<String>read(TestAuth.body(post(ownerToken, "/v1/household/members/" + memberId + "/login-code",
                        "")), "$.code")))), "$.token");
        assertThat(JsonPath.<String>read(TestAuth.body(get(noName, "/v1/me")), "$.deviceName")).isEqualTo("Urządzenie");
        // the name of a working device logs in as that device: no duplicate, its earlier token stops working
        String tabletId = JsonPath.read(TestAuth.body(get(tablet, "/v1/me")), "$.deviceId");
        String again = JsonPath.read(TestAuth.body(loginCode(JsonPath.read(TestAuth.body(post(ownerToken,
                "/v1/household/members/" + memberId + "/login-code", "")), "$.code"), "tablet")), "$.token");
        assertThat(JsonPath.<String>read(TestAuth.body(get(again, "/v1/me")), "$.deviceId")).isEqualTo(tabletId);
        assertThat(get(tablet, "/v1/me")).hasStatus(401);

        // a code made for a device logs in as that device, whatever name the new phone sends
        var forDevice = post(ownerToken, "/v1/household/members/" + memberId + "/login-code",
                "{\"deviceId\": \"%s\"}".formatted(tabletId));
        assertThat(forDevice).hasStatus(201);
        // spelled as at the last login
        assertThat(JsonPath.<String>read(TestAuth.body(forDevice), "$.deviceName")).isEqualTo("tablet");
        String asDevice = JsonPath.read(TestAuth.body(loginCode(JsonPath.read(TestAuth.body(forDevice), "$.code"),
                "Pixel")), "$.token");
        assertThat(JsonPath.<String>read(TestAuth.body(get(asDevice, "/v1/me")), "$.deviceId")).isEqualTo(tabletId);
        assertThat(get(again, "/v1/me")).hasStatus(401);
        assertThat(post(ownerToken, "/v1/household/members/" + memberId + "/login-code",
                "{\"deviceId\": \"%s\"}".formatted(UUID.randomUUID()))).hasStatus(404);

        // a revoked device frees its name: the next login with it adds a new device
        assertThat(delete(ownerToken, "/v1/household/devices/" + tabletId)).hasStatus(204);
        String fresh = JsonPath.read(TestAuth.body(loginCode(JsonPath.read(TestAuth.body(post(ownerToken,
                "/v1/household/members/" + memberId + "/login-code", "")), "$.code"), "Tablet")), "$.token");
        assertThat(JsonPath.<String>read(TestAuth.body(get(fresh, "/v1/me")), "$.deviceId")).isNotEqualTo(tabletId);
        assertThat(get(asDevice, "/v1/me")).hasStatus(401);

        // not for a user of another household or an unknown one
        String other = TestAuth.enroll(mvc, "Other-" + sfx, "phone");
        String otherId = JsonPath.read(TestAuth.body(get(other, "/v1/me")), "$.userId");
        assertThat(post(ownerToken, "/v1/household/members/" + otherId + "/login-code", "")).hasStatus(404);
        assertThat(post(ownerToken, "/v1/household/members/" + UUID.randomUUID() + "/login-code", "")).hasStatus(404);

        // a code of a removed user no longer works
        String late = JsonPath.read(TestAuth.body(post(ownerToken, "/v1/household/members/" + memberId + "/login-code",
                "")), "$.code");
        assertThat(delete(ownerToken, "/v1/household/members/" + memberId)).hasStatus(204);
        assertThat(error(loginCode(late, "Laptop"))).isEqualTo("invalid_login_code");
    }

    @Test
    void theDefaultHouseholdHasNoRecipeLimit() {
        String token = TestAuth.enroll(mvc, "Default-" + sfx, "phone");
        assertThat(JsonPath.<Object>read(TestAuth.body(get(token, "/v1/me")), "$.household.recipeLimit")).isNull();
        assertThat(JsonPath.<String>read(TestAuth.body(get(token, "/v1/me")), "$.household.householdId"))
                .isEqualTo(Households.DEFAULT_ID.toString());
    }

    @Test
    void anInvitationAddsOneMember() {
        var invitation = post(ownerToken, "/v1/household/invitations", "");
        assertThat(invitation).hasStatus(201);
        String code = JsonPath.read(TestAuth.body(invitation), "$.code");
        assertThat(code).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(TestAuth.body(get(ownerToken, "/v1/household/invitations")))
                .contains(JsonPath.<String>read(TestAuth.body(invitation), "$.invitationId")).doesNotContain(code);

        // typed by hand: lower case, without the dash
        var joined = join(code.toLowerCase().replace("-", ""), "Member-" + sfx);
        assertThat(joined).hasStatus(201);
        assertThat(JsonPath.<String>read(TestAuth.body(joined), "$.householdId")).isEqualTo(householdId);
        String memberToken = JsonPath.read(TestAuth.body(joined), "$.token");
        assertThat(JsonPath.<String>read(TestAuth.body(get(memberToken, "/v1/me")), "$.role")).isEqualTo("member");

        assertThat(error(join(code, "Second-" + sfx))).isEqualTo("invalid_invitation_code");
        assertThat(TestAuth.body(get(ownerToken, "/v1/household/invitations"))).isEqualTo("[]");

        // only owners invite
        assertThat(error(post(memberToken, "/v1/household/invitations", ""))).isEqualTo("owner_required");
        assertThat(error(get(memberToken, "/v1/household/invitations"))).isEqualTo("owner_required");
    }

    @Test
    void aRevokedInvitationNoLongerWorks() {
        var invitation = TestAuth.body(post(ownerToken, "/v1/household/invitations", ""));
        assertThat(delete(ownerToken, "/v1/household/invitations/" + JsonPath.read(invitation, "$.invitationId")))
                .hasStatus(204);
        assertThat(error(join(JsonPath.read(invitation, "$.code"), "Late-" + sfx))).isEqualTo("invalid_invitation_code");
        assertThat(delete(ownerToken, "/v1/household/invitations/" + UUID.randomUUID())).hasStatus(404);
    }

    @Test
    void ownersManageMembersAndTheLastOwnerStays() {
        String memberToken = member("Member-" + sfx);
        String members = TestAuth.body(get(ownerToken, "/v1/household/members"));
        assertThat(JsonPath.<List<String>>read(members, "$[*].userName")).containsExactly("Owner-" + sfx, "Member-" + sfx);
        assertThat(JsonPath.<List<Boolean>>read(members, "$[0].devices[*].current")).containsExactly(true);
        String ownerId = JsonPath.read(members, "$[0].userId");
        String memberId = JsonPath.read(members, "$[1].userId");

        assertThat(error(patch(ownerToken, "/v1/household/members/" + ownerId, "{\"role\": \"member\"}")))
                .isEqualTo("last_owner");
        assertThat(error(patch(memberToken, "/v1/household/members/" + memberId, "{\"role\": \"owner\"}")))
                .isEqualTo("owner_required");
        assertThat(error(delete(ownerToken, "/v1/me"))).isEqualTo("last_owner");

        // only a user with an e-mail address becomes an owner
        assertThat(error(patch(ownerToken, "/v1/household/members/" + memberId, "{\"role\": \"owner\"}")))
                .isEqualTo("member_email_required");
        assertThat(patch(memberToken, "/v1/me", "{\"email\": \"member-" + sfx + "@example.org\"}")).hasStatusOk();
        assertThat(patch(ownerToken, "/v1/household/members/" + memberId, "{\"role\": \"owner\"}")).hasStatusOk();
        assertThat(patch(ownerToken, "/v1/household/members/" + ownerId, "{\"role\": \"member\"}")).hasStatusOk();
        // the former owner is a member now; the new owner removes it
        assertThat(error(delete(ownerToken, "/v1/household/members/" + memberId))).isEqualTo("owner_required");
        assertThat(delete(memberToken, "/v1/household/members/" + ownerId)).hasStatus(204);
        assertThat(get(ownerToken, "/v1/me")).hasStatus(401);
        assertThat(error(delete(memberToken, "/v1/household/members/" + memberId))).isEqualTo("invalid_request");
    }

    @Test
    void membersRevokeOnlyTheirOwnDevices() {
        String memberToken = member("Member-" + sfx);
        String members = TestAuth.body(get(ownerToken, "/v1/household/members"));
        String ownerDevice = JsonPath.read(members, "$[0].devices[0].deviceId");
        String memberDevice = JsonPath.read(members, "$[1].devices[0].deviceId");

        assertThat(error(delete(memberToken, "/v1/household/devices/" + ownerDevice))).isEqualTo("owner_required");
        assertThat(delete(memberToken, "/v1/household/devices/" + memberDevice)).hasStatus(204);
        assertThat(get(memberToken, "/v1/me")).hasStatus(401);
        // the member logs in again with the freed name: a new device, which the owner revokes
        String again = JsonPath.read(TestAuth.body(post(null, "/v1/auth/login", """
                {"userName": "Member-%s", "password": "hasło", "deviceName": "Pixel"}""".formatted(sfx))), "$.token");
        String newDevice = JsonPath.read(TestAuth.body(get(again, "/v1/me")), "$.deviceId");
        assertThat(newDevice).isNotEqualTo(memberDevice);
        assertThat(delete(ownerToken, "/v1/household/devices/" + newDevice)).hasStatus(204);
        assertThat(get(again, "/v1/me")).hasStatus(401);
    }

    @Test
    void membersDevicesAndInvitationsOfAnotherHouseholdAreNotFound() {
        String other = TestAuth.enroll(mvc, "Other-" + sfx, "phone");
        jdbc.sql("UPDATE app_user SET role = 'owner', email = :email WHERE name = :name")
                .param("email", "other-" + sfx + "@example.org").param("name", "Other-" + sfx).update();
        String members = TestAuth.body(get(ownerToken, "/v1/household/members"));
        String ownerId = JsonPath.read(members, "$[0].userId");
        String ownerDevice = JsonPath.read(members, "$[0].devices[0].deviceId");
        String invitation = JsonPath.read(TestAuth.body(post(ownerToken, "/v1/household/invitations", "")),
                "$.invitationId");

        assertThat(TestAuth.body(get(other, "/v1/household/members"))).doesNotContain(ownerId);
        assertThat(patch(other, "/v1/household/members/" + ownerId, "{\"role\": \"member\"}")).hasStatus(404);
        assertThat(delete(other, "/v1/household/members/" + ownerId)).hasStatus(404);
        assertThat(delete(other, "/v1/household/devices/" + ownerDevice)).hasStatus(404);
        assertThat(TestAuth.body(get(other, "/v1/household/invitations"))).doesNotContain(invitation);
        assertThat(delete(other, "/v1/household/invitations/" + invitation)).hasStatus(404);
        assertThat(get(ownerToken, "/v1/me")).hasStatusOk();
        // and the default household cannot be deleted
        assertThat(error(delete(other, "/v1/household"))).isEqualTo("default_household");
    }

    @Test
    void theLastUserTakesTheHouseholdAlong() {
        String memberToken = member("Member-" + sfx);
        assertThat(delete(memberToken, "/v1/me")).hasStatus(204);
        assertThat(get(memberToken, "/v1/me")).hasStatus(401);
        assertThat(deletedAt()).isNull();

        assertThat(delete(ownerToken, "/v1/me")).hasStatus(204);
        assertThat(get(ownerToken, "/v1/me")).hasStatus(401);
        assertThat(deletedAt()).isNotNull();
    }

    @Test
    void anOwnerDeletesTheHouseholdWithAllUsers() {
        String memberToken = member("Member-" + sfx);
        String invitation = JsonPath.read(TestAuth.body(post(ownerToken, "/v1/household/invitations", "")), "$.code");
        assertThat(error(delete(memberToken, "/v1/household"))).isEqualTo("owner_required");

        assertThat(delete(ownerToken, "/v1/household")).hasStatus(204);
        assertThat(get(ownerToken, "/v1/me")).hasStatus(401);
        assertThat(get(memberToken, "/v1/me")).hasStatus(401);
        assertThat(deletedAt()).isNotNull();
        assertThat(error(join(invitation, "Late-" + sfx))).isEqualTo("invalid_invitation_code");
        // the names are free again
        assertThat(post(null, "/v1/auth/households", """
                {"householdName": "Znowu", "userName": "Owner-%s", "password": "x", "deviceName": "Pixel",
                 "email": "owner-%s@example.org"}"""
                .formatted(sfx, sfx))).hasStatus(201);
    }

    @Test
    void theRecipeLimitStopsNewRecipesAndTheAdminChangesIt() {
        assertThat(adminPatch("{\"recipeLimit\": 1}")).hasStatusOk();
        TestCaptures.newRecipe(mvc, ownerToken, worker,
                TestCaptures.webCapture(UUID.randomUUID(), "https://limit-" + sfx + ".pl/1", "Pierwszy " + sfx));

        UUID second = TestCaptures.upload(mvc, ownerToken,
                TestCaptures.webCapture(UUID.randomUUID(), "https://limit-" + sfx + ".pl/2", "Drugi " + sfx));
        worker.drain();
        var refused = TestCaptures.resolve(mvc, ownerToken, second, "{\"action\":\"NEW\"}");
        assertThat(refused).hasStatus(409);
        assertThat(error(refused)).isEqualTo("recipe_limit_reached");

        assertThat(adminPatch("{\"recipeLimit\": null}")).hasStatusOk();
        assertThat(TestCaptures.resolve(mvc, ownerToken, second, "{\"action\":\"NEW\"}")).hasStatusOk();
        String household = TestAuth.body(adminPatch("{\"name\": \"Limit " + sfx + "\"}"));
        assertThat(JsonPath.<Integer>read(household, "$.recipes")).isEqualTo(2);
        assertThat(JsonPath.<Object>read(household, "$.recipeLimit")).isNull();
        assertThat(error(adminPatch("{\"recipeLimit\": -1}"))).isEqualTo("invalid_request");
    }

    @Test
    void decisionsStoredBeforeProcessingWaitWhenTheLimitIsReached() {
        assertThat(adminPatch("{\"recipeLimit\": 1}")).hasStatusOk();
        UUID first = TestCaptures.upload(mvc, ownerToken,
                TestCaptures.webCapture(UUID.randomUUID(), "https://wait-" + sfx + ".pl/1", "Pierwszy " + sfx));
        UUID second = TestCaptures.upload(mvc, ownerToken,
                TestCaptures.webCapture(UUID.randomUUID(), "https://wait-" + sfx + ".pl/2", "Drugi " + sfx));
        assertThat(TestCaptures.resolve(mvc, ownerToken, first, "{\"action\":\"NEW\"}")).hasStatusOk();
        assertThat(TestCaptures.resolve(mvc, ownerToken, second, "{\"action\":\"NEW\"}")).hasStatusOk();
        worker.drain();

        List<String> states = List.of(state(first), state(second));
        assertThat(states).containsExactlyInAnyOrder("done", "needs_attention");
        UUID waiting = state(first).equals("needs_attention") ? first : second;
        assertThat(TestAuth.body(TestCaptures.status(mvc, ownerToken, waiting))).contains("RECIPE_LIMIT_REACHED");

        assertThat(adminPatch("{\"recipeLimit\": 2}")).hasStatusOk();
        assertThat(TestCaptures.resolve(mvc, ownerToken, waiting, "{\"action\":\"NEW\"}")).hasStatusOk();
        assertThat(state(waiting)).isEqualTo("done");
    }

    @Test
    void theHouseholdLimitStopsRegistration() {
        int households = jdbc.sql("SELECT count(*) FROM household WHERE deleted_at IS NULL").query(Integer.class).single();
        var full = new HouseholdService(jdbc, accounts, tokens, rateLimiter,
                new HouseholdProperties(households, 40, 10, Duration.ofHours(48), BigDecimal.ONE),
                new RegistrationProperties(null, 10), budget);
        assertThatThrownBy(() -> full.createHousehold("Pełny", "Full-" + sfx, "x", "Pixel", "full-" + sfx + "@example.org"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("household_limit_reached"));

        var slow = new HouseholdService(jdbc, accounts, tokens, rateLimiter,
                new HouseholdProperties(households + 10, 40, 1, Duration.ofHours(48), BigDecimal.ONE),
                new RegistrationProperties(null, 10), budget);
        slow.createHousehold("Jeden", "One-" + sfx, "x", "Pixel", "one-" + sfx + "@example.org");
        assertThatThrownBy(() -> slow.createHousehold("Dwa", "Two-" + sfx, "x", "Pixel", "two-" + sfx + "@example.org"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("too_many_requests"));
    }

    @Test
    void theAdminListsHouseholdsAndEnrollsIntoOne() {
        String list = TestAuth.body(mvc.get().uri("/v1/admin/households")
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()).exchange());
        assertThat(JsonPath.<List<String>>read(list, "$[*].householdId"))
                .contains(Households.DEFAULT_ID.toString(), householdId);

        var enrolled = mvc.post().uri("/v1/admin/enrollments").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\": \"Tablet-%s\", \"deviceName\": \"Tablet\", \"householdId\": \"%s\"}"
                        .formatted(sfx, householdId))
                .exchange();
        assertThat(enrolled).hasStatus(201);
        String tablet = JsonPath.read(TestAuth.body(enrolled), "$.token");
        assertThat(JsonPath.<String>read(TestAuth.body(get(tablet, "/v1/me")), "$.household.householdId"))
                .isEqualTo(householdId);
        assertThat(mvc.post().uri("/v1/admin/enrollments").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\": \"X-%s\", \"deviceName\": \"T\", \"householdId\": \"%s\"}"
                        .formatted(sfx, UUID.randomUUID()))).hasStatus(404);
        assertThat(mvc.patch().uri("/v1/admin/households/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(404);
    }

    private String member(String userName) {
        String code = JsonPath.read(TestAuth.body(post(ownerToken, "/v1/household/invitations", "")), "$.code");
        var joined = join(code, userName);
        assertThat(joined).hasStatus(201);
        return JsonPath.read(TestAuth.body(joined), "$.token");
    }

    private MvcTestResult join(String code, String userName) {
        return post(null, "/v1/auth/join", """
                {"invitationCode": "%s", "userName": "%s", "password": "hasło", "deviceName": "Pixel"}"""
                .formatted(code, userName));
    }

    private MvcTestResult loginCode(String code, String deviceName) {
        return post(null, "/v1/auth/login-code", """
                {"loginCode": "%s", "deviceName": "%s"}""".formatted(code, deviceName));
    }

    private Object deletedAt() {
        return jdbc.sql("SELECT deleted_at FROM household WHERE id = :id").param("id", UUID.fromString(householdId))
                .query((rs, n) -> rs.getObject("deleted_at")).list().getFirst();
    }

    private String state(UUID captureId) {
        return JsonPath.read(TestAuth.body(TestCaptures.status(mvc, ownerToken, captureId)), "$.status");
    }

    private MvcTestResult adminPatch(String body) {
        return mvc.patch().uri("/v1/admin/households/{id}", householdId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult get(String token, String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private MvcTestResult delete(String token, String uri) {
        return mvc.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private MvcTestResult patch(String token, String uri, String body) {
        return mvc.patch().uri(uri).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult post(String token, String uri, String body) {
        var request = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token));
        }
        return request.exchange();
    }

    private static String error(MvcTestResult result) {
        return JsonPath.read(TestAuth.body(result), "$.error");
    }
}
