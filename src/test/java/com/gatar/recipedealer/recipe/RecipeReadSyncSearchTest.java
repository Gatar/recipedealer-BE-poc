package com.gatar.recipedealer.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class RecipeReadSyncSearchTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    String token;
    String suffix;

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Reader", "tablet");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        worker.drain();
    }

    private UUID newRecipe(String slug, String title) {
        return TestCaptures.newRecipe(mvc, token, worker,
                TestCaptures.webCapture(UUID.randomUUID(), "https://read-" + suffix + ".pl/" + slug, title));
    }

    private UUID newRecipe(String slug, String title, List<String> ingredients) {
        ObjectNode capture = TestCaptures.webCapture(UUID.randomUUID(), "https://read-" + suffix + ".pl/" + slug, title);
        var group = capture.withObject("recipe").putArray("ingredientGroups").addObject();
        var items = group.putArray("items");
        ingredients.forEach(items::add);
        return TestCaptures.newRecipe(mvc, token, worker, capture);
    }

    private MvcTestResult get(String uri, Object... vars) {
        return mvc.get().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    @Test
    void recipeReturnsHeadContentWithEtag() {
        UUID recipeId = newRecipe("schabowy", "Schabowy " + suffix);
        UUID head = jdbc.sql("SELECT head_revision_id FROM recipe WHERE id = :id").param("id", recipeId).query(UUID.class).single();

        var result = get("/v1/recipes/{id}", recipeId);
        assertThat(result).hasStatusOk().hasHeader(HttpHeaders.ETAG, "\"" + head + "\"");
        String body = TestAuth.body(result);
        assertThat(JsonPath.<String>read(body, "$.recipe.title")).isEqualTo("Schabowy " + suffix);
        assertThat(JsonPath.<String>read(body, "$.recipe.ingredientGroups[0].name")).isEqualTo("Jabłka");
        assertThat(JsonPath.<String>read(body, "$.origin")).isEqualTo("MACHINE");
        assertThat(JsonPath.<List<String>>read(body, "$.urls")).containsExactly("https://read-" + suffix + ".pl/schabowy");

        assertThat(mvc.get().uri("/v1/recipes/{id}", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .header(HttpHeaders.IF_NONE_MATCH, "\"" + head + "\"")).hasStatus(304);
        assertThat(get("/v1/recipes/{id}", UUID.randomUUID())).hasStatus(404);
    }

    @Test
    void deletedAndMergedRecipesAreGone() {
        UUID deleted = newRecipe("gone-1", "Usunięty " + suffix);
        UUID merged = newRecipe("gone-2", "Scalony " + suffix);
        UUID target = newRecipe("gone-3", "Docelowy " + suffix);
        jdbc.sql("UPDATE recipe SET deleted_at = now(), updated_at = now() WHERE id = :id").param("id", deleted).update();
        jdbc.sql("UPDATE recipe SET merged_into = :target, updated_at = now() WHERE id = :id")
                .param("id", merged).param("target", target).update();

        assertThat(get("/v1/recipes/{id}", deleted)).hasStatus(410).bodyJson().extractingPath("$.error").isEqualTo("recipe_deleted");
        assertThat(get("/v1/recipes/{id}", merged)).hasStatus(410).bodyJson().extractingPath("$.details[0]")
                .asString().contains(target.toString());
    }

    @Test
    void revisionsAreListedNewestFirstWithHeadFlag() {
        UUID recipeId = newRecipe("kotlet", "Kotlet " + suffix);
        UUID captureId = TestCaptures.upload(mvc, token,
                TestCaptures.webCapture(UUID.randomUUID(), "https://read-" + suffix + ".pl/kotlet", "Kotlet mielony " + suffix));
        worker.drain();
        TestCaptures.resolve(mvc, token, captureId, "{\"action\":\"UPDATE\",\"recipeId\":\"" + recipeId + "\"}");

        String body = TestAuth.body(get("/v1/recipes/{id}/revisions", recipeId));
        assertThat(JsonPath.<List<Boolean>>read(body, "$[*].head")).containsExactly(true, false);
        assertThat(JsonPath.<String>read(body, "$[0].recipe.title")).isEqualTo("Kotlet mielony " + suffix);
        assertThat(JsonPath.<String>read(body, "$[0].parentId")).isEqualTo(JsonPath.read(body, "$[1].revisionId"));
        assertThat(JsonPath.<String>read(body, "$[0].userName")).isEqualTo("Reader");
        assertThat(get("/v1/recipes/{id}/revisions", UUID.randomUUID())).hasStatus(404);
    }

    @Test
    void syncFeedPagesThroughChangesAndReportsTombstones() {
        String before = JsonPath.read(TestAuth.body(get("/v1/recipes?limit=1")), "$.nextCursor");
        String since = jdbc.sql("SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")
                .query(String.class).single();
        UUID a = newRecipe("sync-a", "Sync A " + suffix);
        UUID b = newRecipe("sync-b", "Sync B " + suffix);
        UUID c = newRecipe("sync-c", "Sync C " + suffix);
        jdbc.sql("UPDATE recipe SET deleted_at = now(), updated_at = clock_timestamp() WHERE id = :id").param("id", b).update();

        List<String> ids = new ArrayList<>();
        List<Boolean> deletedFlags = new ArrayList<>();
        String cursor = null;
        boolean more = true;
        int pages = 0;
        while (more) {
            String body = TestAuth.body(cursor == null
                    ? get("/v1/recipes?updatedSince={since}&limit=2", since)
                    : get("/v1/recipes?cursor={cursor}&limit=2", cursor));
            ids.addAll(JsonPath.read(body, "$.items[*].recipeId"));
            deletedFlags.addAll(JsonPath.read(body, "$.items[*].deleted"));
            cursor = JsonPath.read(body, "$.nextCursor");
            more = JsonPath.read(body, "$.hasMore");
            pages++;
        }

        assertThat(pages).isGreaterThanOrEqualTo(2);
        assertThat(ids).containsSubsequence(a.toString(), c.toString(), b.toString()).doesNotHaveDuplicates();
        assertThat(deletedFlags.get(ids.indexOf(b.toString()))).isTrue();

        String tombstone = TestAuth.body(get("/v1/recipes?updatedSince={since}&limit=100", since));
        assertThat(JsonPath.<List<Object>>read(tombstone, "$.items[?(@.recipeId == '" + b + "')].recipe")).isEmpty();
        assertThat(JsonPath.<List<String>>read(tombstone, "$.items[?(@.recipeId == '" + a + "')].recipe.title"))
                .containsExactly("Sync A " + suffix);

        String after = TestAuth.body(get("/v1/recipes?cursor={cursor}", cursor));
        assertThat(JsonPath.<List<?>>read(after, "$.items")).isEmpty();
        assertThat(JsonPath.<String>read(after, "$.nextCursor")).isEqualTo(cursor);

        String full = TestAuth.body(get("/v1/recipes?cursor={cursor}&limit=500", before));
        assertThat(JsonPath.<List<String>>read(full, "$.items[*].recipeId")).contains(a.toString(), c.toString());
        assertThat(get("/v1/recipes?cursor=garbage")).hasStatus(400);
    }

    @Test
    void searchFindsPolishTitlesWithoutDiacriticsAndWithTypos() {
        UUID zurek = newRecipe("zurek", "Żurek na zakwasie " + suffix);
        UUID pierogi = newRecipe("pierogi", "Pierogi ruskie " + suffix);
        UUID losos = newRecipe("losos", "Łosoś z koperkiem " + suffix);

        assertThat(searchIds("zurek " + suffix)).contains(zurek.toString());
        assertThat(searchIds("ŻUREK NA ZAKWASIE " + suffix)).first().isEqualTo(zurek.toString());
        assertThat(searchIds("losos " + suffix)).contains(losos.toString());
        assertThat(searchIds("piergi ruskei " + suffix)).contains(pierogi.toString());
        assertThat(searchIds("zurke " + suffix)).contains(zurek.toString());
    }

    @Test
    void searchMatchesIngredients() {
        UUID recipeId = newRecipe("placek", "Placek " + suffix, List.of("200 g truskawek", "1 szklanka mąki " + suffix));

        assertThat(searchIds("truskawki " + suffix)).contains(recipeId.toString());
        assertThat(searchIds("maka " + suffix)).contains(recipeId.toString());
    }

    @Test
    void searchSkipsDeletedRecipes() {
        UUID recipeId = newRecipe("bez", "Beza " + suffix);
        jdbc.sql("UPDATE recipe SET deleted_at = now(), updated_at = now() WHERE id = :id").param("id", recipeId).update();

        assertThat(searchIds("beza " + suffix)).doesNotContain(recipeId.toString());
    }

    private List<String> searchIds(String q) {
        var result = get("/v1/recipes/search?q={q}", q);
        assertThat(result).hasStatusOk();
        return JsonPath.read(TestAuth.body(result), "$[*].recipeId");
    }
}
