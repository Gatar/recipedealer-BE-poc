package com.gatar.recipedealer.tag;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class TagTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    String token;
    /** Tests share one database and the catalog is global: every test uses categories of its own. */
    String sfx;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Tagger", "phone");
        sfx = UUID.randomUUID().toString().substring(0, 8);
        worker.drain();
    }

    @Test
    void catalogKeepsTheUsersOrderAndDerivesNames() {
        String meal = "meal-" + sfx;
        assertThat(post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Posiłek\"}".formatted(meal))).hasStatusOk();
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"dinner\",\"title\":\"Obiad\"}", meal);
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"supper\",\"title\":\"Kolacja\"}", meal);
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"breakfast\",\"title\":\"Śniadanie\",\"position\":0}", meal);
        String body = TestAuth.body(post("/v1/tags/categories/{c}/tags", "{\"title\":\"Danie główne " + sfx + "\"}", meal));

        assertThat(names(body, meal)).containsExactly("breakfast", "dinner", "supper", "danie-glowne-" + sfx);
        assertThat(JsonPath.<List<String>>read(body, "$.categories[?(@.name == '" + meal + "')].tags[0].id"))
                .containsExactly(meal + ":breakfast");
        assertThat(JsonPath.<List<String>>read(body, "$.categories[?(@.name == '" + meal + "')].title")).containsExactly("Posiłek");

        body = TestAuth.body(patch("/v1/tags/categories/{c}/tags/{t}", "{\"position\":1,\"title\":\"Wieczerza\"}", meal, "supper"));
        assertThat(names(body, meal)).containsExactly("breakfast", "supper", "dinner", "danie-glowne-" + sfx);
        assertThat(JsonPath.<List<String>>read(body, "$.categories[?(@.name == '" + meal + "')].tags[1].title"))
                .containsExactly("Wieczerza");

        String kind = "kind-" + sfx;
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Rodzaj\"}".formatted(kind));
        body = TestAuth.body(patch("/v1/tags/categories/{c}", "{\"position\":0}", kind));
        List<String> categories = JsonPath.read(body, "$.categories[*].name");
        assertThat(categories.indexOf(kind)).isLessThan(categories.indexOf(meal));
    }

    @Test
    void invalidNamesAndDuplicatesAreRejected() {
        String meal = "meal-" + sfx;
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Posiłek\"}".formatted(meal));
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"dinner\",\"title\":\"Obiad\"}", meal);

        assertThat(post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Again\"}".formatted(meal)))
                .hasStatus(409).bodyJson().extractingPath("$.error").isEqualTo("tag_exists");
        assertThat(post("/v1/tags/categories/{c}/tags", "{\"name\":\"dinner\",\"title\":\"Obiad 2\"}", meal)).hasStatus(409);
        assertThat(post("/v1/tags/categories", "{\"name\":\"Meal:X\",\"title\":\"Bad\"}")).hasStatus(400);
        assertThat(post("/v1/tags/categories", "{\"title\":\"  \"}")).hasStatus(400);
        assertThat(post("/v1/tags/categories/{c}/tags", "{\"title\":\"Obiad\"}", "missing-" + sfx))
                .hasStatus(404).bodyJson().extractingPath("$.error").isEqualTo("not_found");
        assertThat(patch("/v1/tags/categories/{c}/tags/{t}", "{\"title\":\"X\"}", meal, "missing"))
                .hasStatus(404).bodyJson().extractingPath("$.error").isEqualTo("not_found");
    }

    @Test
    void recipeTagsComeInCatalogOrderAndBumpTheSyncFeed() {
        String meal = "meal-" + sfx;
        String kind = "kind-" + sfx;
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Posiłek\"}".formatted(meal));
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Rodzaj\"}".formatted(kind));
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"dinner\",\"title\":\"Obiad\"}", meal);
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"supper\",\"title\":\"Kolacja\"}", meal);
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"soup\",\"title\":\"Zupa\"}", kind);
        UUID recipeId = newRecipe("zupa", "Zupa " + sfx);
        String since = now();

        var result = put("/v1/recipes/{id}/tags", "{\"tags\":[\"%s:soup\",\"%s:supper\",\"%s:dinner\"]}".formatted(kind, meal, meal), recipeId);
        assertThat(result).hasStatusOk();
        assertThat(JsonPath.<List<String>>read(TestAuth.body(result), "$.tags"))
                .containsExactly(meal + ":dinner", meal + ":supper", kind + ":soup");
        assertThat(JsonPath.<List<String>>read(TestAuth.body(get("/v1/recipes/{id}", recipeId)), "$.tags"))
                .containsExactly(meal + ":dinner", meal + ":supper", kind + ":soup");
        assertThat(JsonPath.<List<List<String>>>read(TestAuth.body(get("/v1/recipes?updatedSince={s}&limit=500", since)),
                "$.items[?(@.recipeId == '" + recipeId + "')].tags")).containsExactly(List.of(meal + ":dinner", meal + ":supper", kind + ":soup"));
        assertThat(JsonPath.<List<Integer>>read(TestAuth.body(get("/v1/tags")),
                "$.categories[?(@.name == '" + meal + "')].tags[0].recipeCount")).containsExactly(1);

        String unchanged = now();
        put("/v1/recipes/{id}/tags", "{\"tags\":[\"%s:dinner\",\"%s:supper\",\"%s:soup\"]}".formatted(meal, meal, kind), recipeId);
        assertThat(JsonPath.<List<String>>read(TestAuth.body(get("/v1/recipes?updatedSince={s}&limit=500", unchanged)),
                "$.items[*].recipeId")).doesNotContain(recipeId.toString());

        assertThat(put("/v1/recipes/{id}/tags", "{\"tags\":[\"%s:nope\"]}".formatted(meal), recipeId))
                .hasStatus(400).bodyJson().extractingPath("$.error").isEqualTo("unknown_tag");
        assertThat(put("/v1/recipes/{id}/tags", "{\"tags\":[]}", UUID.randomUUID())).hasStatus(404);

        jdbc.sql("UPDATE recipe SET deleted_at = now(), updated_at = now() WHERE id = :id").param("id", recipeId).update();
        assertThat(JsonPath.<List<Integer>>read(TestAuth.body(get("/v1/tags")),
                "$.categories[?(@.name == '" + meal + "')].tags[0].recipeCount")).containsExactly(0);
        assertThat(put("/v1/recipes/{id}/tags", "{\"tags\":[]}", recipeId)).hasStatus(410);
    }

    @Test
    void renamingMovingAndDeletingCarryOverToRecipes() {
        String meal = "meal-" + sfx;
        String kind = "kind-" + sfx;
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Posiłek\"}".formatted(meal));
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Rodzaj\"}".formatted(kind));
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"dinner\",\"title\":\"Obiad\"}", meal);
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"soup\",\"title\":\"Zupa\"}", meal);
        UUID recipeId = newRecipe("rosol", "Rosół " + sfx);
        put("/v1/recipes/{id}/tags", "{\"tags\":[\"%s:dinner\",\"%s:soup\"]}".formatted(meal, meal), recipeId);

        String since = now();
        String renamed = "posilek-" + sfx;
        assertThat(patch("/v1/tags/categories/{c}", "{\"name\":\"%s\"}".formatted(renamed), meal)).hasStatusOk();
        assertThat(syncedTags(since, recipeId)).containsExactly(renamed + ":dinner", renamed + ":soup");

        since = now();
        String body = TestAuth.body(patch("/v1/tags/categories/{c}/tags/{t}", "{\"category\":\"%s\",\"name\":\"zupa\"}".formatted(kind), renamed, "soup"));
        assertThat(names(body, renamed)).containsExactly("dinner");
        assertThat(names(body, kind)).containsExactly("zupa");
        assertThat(syncedTags(since, recipeId)).containsExactly(renamed + ":dinner", kind + ":zupa");

        since = now();
        assertThat(delete("/v1/tags/categories/{c}/tags/{t}", renamed, "dinner")).hasStatusOk();
        assertThat(syncedTags(since, recipeId)).containsExactly(kind + ":zupa");

        since = now();
        body = TestAuth.body(delete("/v1/tags/categories/{c}", kind));
        assertThat(JsonPath.<List<String>>read(body, "$.categories[*].name")).doesNotContain(kind);
        assertThat(syncedTags(since, recipeId)).isEmpty();
    }

    @Test
    void tagsChosenWithANewDecisionGoToTheNewRecipe() {
        String meal = "meal-" + sfx;
        post("/v1/tags/categories", "{\"name\":\"%s\",\"title\":\"Posiłek\"}".formatted(meal));
        post("/v1/tags/categories/{c}/tags", "{\"name\":\"dinner\",\"title\":\"Obiad\"}", meal);
        UUID existing = newRecipe("istniejacy", "Istniejący " + sfx);

        UUID captureId = TestCaptures.upload(mvc, token,
                TestCaptures.webCapture(UUID.randomUUID(), "https://tags-" + sfx + ".pl/nowy", "Nowy " + sfx));
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\",\"tags\":[\"%s:nope\"]}".formatted(meal)))
                .hasStatus(400);
        assertThat(TestCaptures.resolve(mvc, token, captureId,
                "{\"action\":\"UPDATE\",\"recipeId\":\"%s\",\"tags\":[\"%s:dinner\"]}".formatted(existing, meal))).hasStatus(400);
        // the decision arrives before processing: stored, applied when the candidate exists
        assertThat(TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"NEW\",\"tags\":[\"%s:dinner\"]}".formatted(meal)))
                .hasStatusOk();
        worker.drain();

        String view = TestAuth.body(TestCaptures.status(mvc, token, captureId));
        UUID recipeId = UUID.fromString(JsonPath.read(view, "$.recipes[0].recipeId"));
        assertThat(JsonPath.<List<String>>read(TestAuth.body(get("/v1/recipes/{id}", recipeId)), "$.tags"))
                .containsExactly(meal + ":dinner");
    }

    @Test
    void slugDropsPolishDiacritics() {
        assertThat(TagService.slug("Danie główne")).isEqualTo("danie-glowne");
        assertThat(TagService.slug("  Łosoś & Żurek!  ")).isEqualTo("losos-zurek");
    }

    private UUID newRecipe(String slug, String title) {
        return TestCaptures.newRecipe(mvc, token, worker,
                TestCaptures.webCapture(UUID.randomUUID(), "https://tags-" + sfx + ".pl/" + slug, title));
    }

    private List<String> syncedTags(String since, UUID recipeId) {
        List<List<String>> tags = JsonPath.read(TestAuth.body(get("/v1/recipes?updatedSince={s}&limit=500", since)),
                "$.items[?(@.recipeId == '" + recipeId + "')].tags");
        assertThat(tags).as("recipe in the sync feed").hasSize(1);
        return tags.getFirst();
    }

    private static List<String> names(String catalog, String category) {
        return JsonPath.read(catalog, "$.categories[?(@.name == '" + category + "')].tags[*].name");
    }

    private String now() {
        return jdbc.sql("SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")
                .query(String.class).single();
    }

    private MvcTestResult get(String uri, Object... vars) {
        return mvc.get().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private MvcTestResult post(String uri, String body, Object... vars) {
        return mvc.post().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult patch(String uri, String body, Object... vars) {
        return mvc.patch().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult put(String uri, String body, Object... vars) {
        return mvc.put().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult delete(String uri, Object... vars) {
        return mvc.delete().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }
}
