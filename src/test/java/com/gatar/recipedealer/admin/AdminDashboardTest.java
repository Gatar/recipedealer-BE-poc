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
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.auth.AdminAuthRateLimiter;
import com.gatar.recipedealer.household.Households;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class AdminDashboardTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JobWorker worker;

    @Autowired
    AdminAuthRateLimiter rateLimiter;

    String sfx;
    String token;
    UUID recipeId;
    UUID pending;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        sfx = UUID.randomUUID().toString().substring(0, 8);
        token = TestAuth.enroll(mvc, "Dash-" + sfx, "phone");
        recipeId = TestCaptures.newRecipe(mvc, token, worker,
                TestCaptures.webCapture(UUID.randomUUID(), "https://dash-" + sfx + ".pl/x", "Pierogi panel " + sfx));
        pending = TestCaptures.upload(mvc, token,
                TestCaptures.webCapture(UUID.randomUUID(), "https://dash-" + sfx + ".pl/y", "Czeka panel " + sfx));
        worker.drain();
    }

    private MvcTestResult get(String uri, Object... vars) {
        return mvc.get().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()).exchange();
    }

    @Test
    void theDashboardPageNeedsTheAdminLoginWithoutUsingUpTheFailureBudget() {
        // more requests without credentials than the failure budget allows: they must not block the admin
        for (int i = 0; i < 10; i++) {
            var anonymous = mvc.get().uri("/admin/").exchange();
            assertThat(anonymous).hasStatus(401);
            assertThat(anonymous.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
        }
        // MockMvc does not follow the forward to the static page, so check both steps
        assertThat(get("/admin/")).hasStatusOk().hasForwardedUrl("/admin/index.html");
        assertThat(get("/admin")).hasStatusOk().hasForwardedUrl("/admin/index.html");
        var page = get("/admin/index.html");
        assertThat(page).hasStatusOk();
        assertThat(TestAuth.body(page)).contains("panel admina");
        assertThat(get("/admin/admin.js")).hasStatusOk();
        assertThat(page.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'self'");
    }

    @Test
    void changesFromAnotherSiteAreRefused() {
        String body = "{\"name\": \"Default household\"}";
        var foreign = mvc.patch().uri("/v1/admin/households/{id}", Households.DEFAULT_ID)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()).header(HttpHeaders.ORIGIN, "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(foreign).hasStatus(403);
        assertThat(TestAuth.body(foreign)).contains("cross_origin");
        assertThat(mvc.patch().uri("/v1/admin/households/{id}", Households.DEFAULT_ID)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic()).header(HttpHeaders.ORIGIN, "http://localhost")
                .contentType(MediaType.APPLICATION_JSON).content(body)).hasStatusOk();
        // reading from another site is not blocked here (the browser does not let that site see the answer)
        assertThat(mvc.get().uri("/v1/admin/overview").header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .header(HttpHeaders.ORIGIN, "https://evil.example")).hasStatusOk();
    }

    @Test
    void overviewCountsEverything() {
        String overview = TestAuth.body(get("/v1/admin/overview"));
        assertThat(JsonPath.<Integer>read(overview, "$.recipes")).isPositive();
        assertThat(JsonPath.<Integer>read(overview, "$.capturesByStatus.needs_decision")).isPositive();
        assertThat(JsonPath.<Object>read(overview, "$.llmCostTotalUsd")).isNotNull();
    }

    @Test
    void usersAreListedAndTheAdminChangesARole() {
        String users = TestAuth.body(get("/v1/admin/users?householdId={h}", Households.DEFAULT_ID));
        List<String> ids = JsonPath.read(users, "$[?(@.userName == 'Dash-" + sfx + "')].userId");
        assertThat(ids).hasSize(1);
        var changed = mvc.patch().uri("/v1/admin/users/{id}", ids.getFirst())
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"owner\", \"email\": \"dash-" + sfx + "@example.org\"}").exchange();
        assertThat(changed).hasStatusOk();
        assertThat(JsonPath.<String>read(TestAuth.body(changed), "$.role")).isEqualTo("owner");
        assertThat(mvc.patch().uri("/v1/admin/users/{id}", ids.getFirst())
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"king\"}")).hasStatus(400);
        assertThat(mvc.patch().uri("/v1/admin/users/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, TestAuth.adminBasic())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"owner\"}")).hasStatus(404);
    }

    @Test
    void recipesAreSearchedAndShownWithTheirHistory() {
        String list = TestAuth.body(get("/v1/admin/recipes?q={q}", "pierogi PANEL " + sfx));
        assertThat(JsonPath.<List<String>>read(list, "$[*].recipeId")).containsExactly(recipeId.toString());
        String detail = TestAuth.body(get("/v1/admin/recipes/{id}", recipeId));
        assertThat(JsonPath.<String>read(detail, "$.recipe.title")).isEqualTo("Pierogi panel " + sfx);
        assertThat(JsonPath.<String>read(detail, "$.recipe.householdName")).isEqualTo("Default household");
        assertThat(JsonPath.<List<String>>read(detail, "$.urls")).containsExactly("https://dash-" + sfx + ".pl/x");
        assertThat(JsonPath.<Integer>read(detail, "$.revisions.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(detail, "$.content.title")).isEqualTo("Pierogi panel " + sfx);
        assertThat(get("/v1/admin/recipes/{id}", UUID.randomUUID())).hasStatus(404);
    }

    @Test
    void capturesAreFilteredByState() {
        String waiting = TestAuth.body(get("/v1/admin/captures?status=needs_decision&householdId={h}", Households.DEFAULT_ID));
        assertThat(waiting).contains(pending.toString());
        assertThat(JsonPath.<List<String>>read(waiting, "$[*].status")).containsOnly("needs_decision");
        assertThat(TestAuth.body(get("/v1/admin/captures?status=done"))).doesNotContain(pending.toString());
        String all = TestAuth.body(get("/v1/admin/captures"));
        assertThat(JsonPath.<List<String>>read(all, "$[?(@.captureId == '" + pending + "')].userName"))
                .containsExactly("Dash-" + sfx);
    }

    @Test
    void costsAndBlobsAreAvailable() {
        assertThat(get("/v1/admin/llm-costs?months=3")).hasStatusOk();
        assertThat(get("/v1/admin/llm-costs?months=0")).hasStatus(400);
        assertThat(get("/v1/admin/blobs/{sha}", "a".repeat(64))).hasStatus(404);
        assertThat(get("/v1/admin/blobs/{sha}", "nope")).hasStatus(404);
    }
}
