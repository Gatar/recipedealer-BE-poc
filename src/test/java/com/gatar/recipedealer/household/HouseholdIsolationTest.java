package com.gatar.recipedealer.household;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.auth.DeviceTokenService;
import com.gatar.recipedealer.common.Hashing;
import com.gatar.recipedealer.common.Ids;
import com.gatar.recipedealer.processing.JobWorker;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A device sees only its household's data: every device endpoint called with another household's ids answers like
 * for an unknown id, and lists, search and sync never show another household's rows.
 * {@link #everyDeviceEndpointIsCovered} fails when an endpoint is added without a case here.
 */
@IntegrationTest
class HouseholdIsolationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Device endpoints checked below or in {@link HouseholdAccountsTest}; {@code PUT /v1/me/password} only ever acts
     * on the caller itself.
     */
    private static final Set<String> COVERED = Set.of(
            "PUT /v1/captures/{captureId}",
            "GET /v1/captures/{captureId}",
            "GET /v1/captures",
            "PUT /v1/captures/{captureId}/extracted/{index}/resolution",
            "POST /v1/captures/{captureId}/llm-extraction",
            "DELETE /v1/captures/{captureId}",
            "GET /v1/recipes/candidates",
            "GET /v1/recipes",
            "GET /v1/recipes/search",
            "GET /v1/recipes/{recipeId}",
            "GET /v1/recipes/{recipeId}/revisions",
            "GET /v1/recipes/{recipeId}/extraction-stats",
            "POST /v1/recipes/{recipeId}/revisions",
            "DELETE /v1/recipes/{recipeId}",
            "POST /v1/recipes/{recipeId}/lock",
            "PUT /v1/recipes/{recipeId}/lock/{lockId}",
            "DELETE /v1/recipes/{recipeId}/lock/{lockId}",
            "GET /v1/tags",
            "POST /v1/tags/categories",
            "PATCH /v1/tags/categories/{category}",
            "DELETE /v1/tags/categories/{category}",
            "POST /v1/tags/categories/{category}/tags",
            "PATCH /v1/tags/categories/{category}/tags/{tag}",
            "DELETE /v1/tags/categories/{category}/tags/{tag}",
            "PUT /v1/recipes/{recipeId}/tags",
            "POST /v1/blobs",
            "GET /v1/blobs/{sha256}",
            "PUT /v1/me/password",
            // HouseholdAccountsTest: these act on the caller or its household, ids of another household are 404
            "GET /v1/me",
            "DELETE /v1/me",
            "PATCH /v1/household",
            "DELETE /v1/household",
            "GET /v1/household/members",
            "PATCH /v1/household/members/{userId}",
            "DELETE /v1/household/members/{userId}",
            "DELETE /v1/household/devices/{deviceId}",
            "POST /v1/household/invitations",
            "GET /v1/household/invitations",
            "DELETE /v1/household/invitations/{invitationId}");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeviceTokenService tokens;

    @Autowired
    JobWorker worker;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    String sfx;
    String url;
    String title;
    /** A device of the default household and one of a second household. */
    String tokenA;
    String tokenB;
    UUID captureA;
    UUID pendingA;
    UUID recipeA;
    String attachmentA;
    String photoA;
    byte[] photoContent;

    @BeforeEach
    void setUp() throws IOException {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        url = "https://iso-" + sfx + ".pl/bigos";
        title = "Bigos izolacja " + sfx;
        tokenA = TestAuth.enroll(mvc, "IsoA-" + sfx, "phone");
        UUID householdB = Ids.newId();
        jdbc.sql("INSERT INTO household (id, name) VALUES (:id, :name)").param("id", householdB)
                .param("name", "Iso " + sfx).update();
        tokenB = tokens.enroll(householdB, "IsoB-" + sfx, "phone").token();

        captureA = UUID.randomUUID();
        byte[] image = ("jpeg " + captureA).getBytes();
        attachmentA = Hashing.sha256Hex(image);
        var capture = TestCaptures.webCapture(captureA, url, title);
        capture.withArray("attachments").add(TestCaptures.attachment("photo.jpg", "image", "image/jpeg"));
        assertThat(TestCaptures.put(mvc, tokenA, captureA, TestCaptures.capturePart(TestCaptures.bytes(capture)),
                TestCaptures.filePart("photo.jpg", "image/jpeg", image))).hasStatus(201);
        worker.drain();
        var resolved = TestCaptures.resolve(mvc, tokenA, captureA, "{\"action\":\"NEW\"}");
        assertThat(resolved).hasStatusOk();
        recipeA = UUID.fromString(JsonPath.read(TestAuth.body(resolved), "$.recipes[0].recipeId"));

        pendingA = TestCaptures.upload(mvc, tokenA, TestCaptures.webCapture(UUID.randomUUID(),
                "https://iso-" + sfx + ".pl/pending", "Czeka " + sfx));
        worker.drain();

        photoContent = png();
        var photo = uploadPhoto(tokenA, photoContent);
        assertThat(photo).hasStatus(201);
        photoA = JsonPath.read(TestAuth.body(photo), "$.blob");
    }

    @Test
    void everyDeviceEndpointIsCovered() {
        Set<String> device = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(path -> path.startsWith("/v1/") && !path.startsWith("/v1/admin/") && !path.startsWith("/v1/auth/")
                        && !path.equals("/v1/health"))
                .forEach(path -> info.getMethodsCondition().getMethods().forEach(method -> device.add(method + " " + path))));
        assertThat(device).as("device endpoints (left) vs. the cases of this test (right)").isEqualTo(new TreeSet<>(COVERED));
    }

    @Test
    void capturesOfAnotherHouseholdAreNotFound() {
        assertThat(get(tokenB, "/v1/captures/{id}", captureA)).hasStatus(404);
        assertThat(get(tokenA, "/v1/captures/{id}", captureA)).hasStatusOk();

        String all = "accepted,processing,needs_decision,needs_attention,done";
        assertThat(TestAuth.body(get(tokenB, "/v1/captures?status=" + all))).doesNotContain(captureA.toString())
                .doesNotContain(pendingA.toString());
        assertThat(TestAuth.body(get(tokenA, "/v1/captures"))).contains(pendingA.toString());

        assertThat(TestCaptures.resolve(mvc, tokenB, pendingA, "{\"action\":\"NEW\"}")).hasStatus(404);
        assertThat(mvc.post().uri("/v1/captures/{id}/llm-extraction", pendingA).header(HttpHeaders.AUTHORIZATION,
                TestAuth.bearer(tokenB))).hasStatus(404);
        assertThat(mvc.delete().uri("/v1/captures/{id}", pendingA).header(HttpHeaders.AUTHORIZATION,
                TestAuth.bearer(tokenB))).hasStatus(404);
        assertThat(get(tokenA, "/v1/captures/{id}", pendingA)).hasStatusOk();
    }

    @Test
    void captureIdOfAnotherHouseholdIsTaken() {
        var again = TestCaptures.put(mvc, tokenB, pendingA, TestCaptures.capturePart(TestCaptures.bytes(
                TestCaptures.webCapture(pendingA, "https://iso-" + sfx + ".pl/pending", "Czeka " + sfx))));
        assertThat(again).hasStatus(409);
        assertThat(TestAuth.body(again)).contains("capture_id_taken");
    }

    @Test
    void recipesOfAnotherHouseholdAreNotFound() {
        for (String path : List.of("/v1/recipes/{id}", "/v1/recipes/{id}/revisions", "/v1/recipes/{id}/extraction-stats")) {
            assertThat(get(tokenB, path, recipeA)).as(path).hasStatus(404);
            assertThat(get(tokenA, path, recipeA)).as(path).hasStatusOk();
        }
        assertThat(mvc.post().uri("/v1/recipes/{id}/lock", recipeA).header(HttpHeaders.AUTHORIZATION,
                TestAuth.bearer(tokenB))).hasStatus(404);
        String lockId = JsonPath.read(TestAuth.body(mvc.post().uri("/v1/recipes/{id}/lock", recipeA)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenA)).exchange()), "$.lockId");
        assertThat(mvc.put().uri("/v1/recipes/{id}/lock/{lock}", recipeA, lockId).header(HttpHeaders.AUTHORIZATION,
                TestAuth.bearer(tokenB))).hasStatus(404);
        assertThat(mvc.delete().uri("/v1/recipes/{id}/lock/{lock}", recipeA, lockId).header(HttpHeaders.AUTHORIZATION,
                TestAuth.bearer(tokenB))).hasStatus(404);
        String head = JsonPath.read(TestAuth.body(get(tokenA, "/v1/recipes/{id}", recipeA)), "$.headRevisionId");
        assertThat(mvc.post().uri("/v1/recipes/{id}/revisions", recipeA)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB)).header("Lock-Id", lockId)
                .header(HttpHeaders.IF_MATCH, "\"" + head + "\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Przejęty\"}")).hasStatus(404);
        assertThat(mvc.delete().uri("/v1/recipes/{id}", recipeA).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB))
                .header("Lock-Id", lockId)).hasStatus(404);
        assertThat(mvc.put().uri("/v1/recipes/{id}/tags", recipeA).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[]}")).hasStatus(404);
        // the lock is still A's and the recipe still live
        assertThat(mvc.put().uri("/v1/recipes/{id}/lock/{lock}", recipeA, lockId).header(HttpHeaders.AUTHORIZATION,
                TestAuth.bearer(tokenA))).hasStatusOk();
        assertThat(get(tokenA, "/v1/recipes/{id}", recipeA)).hasStatusOk();
    }

    @Test
    void listsSearchAndCandidatesShowOnlyTheOwnHousehold() {
        assertThat(TestAuth.body(get(tokenA, "/v1/recipes/search?q={q}", title))).contains(recipeA.toString());
        assertThat(TestAuth.body(get(tokenB, "/v1/recipes/search?q={q}", title))).doesNotContain(recipeA.toString());
        assertThat(TestAuth.body(get(tokenA, "/v1/recipes/candidates?url={u}", url))).contains(recipeA.toString());
        assertThat(TestAuth.body(get(tokenB, "/v1/recipes/candidates?url={u}&title={t}", url, title)))
                .isEqualTo("[]");

        // the same page in household B is a recipe of its own: identity keys are per household
        UUID recipeB = TestCaptures.newRecipe(mvc, tokenB, worker, TestCaptures.webCapture(UUID.randomUUID(), url, title));
        assertThat(recipeB).isNotEqualTo(recipeA);
        String feed = TestAuth.body(get(tokenB, "/v1/recipes?limit=500"));
        assertThat((List<String>) JsonPath.read(feed, "$.items[*].recipeId")).containsExactly(recipeB.toString());
        assertThat(TestAuth.body(get(tokenA, "/v1/recipes?limit=500&updatedSince=2020-01-01T00:00:00Z")))
                .doesNotContain(recipeB.toString());
    }

    @Test
    void updateCannotTargetAnotherHouseholdsRecipe() {
        UUID captureB = TestCaptures.upload(mvc, tokenB, TestCaptures.webCapture(UUID.randomUUID(),
                "https://iso-" + sfx + ".pl/b", "Inny " + sfx));
        worker.drain();
        var update = TestCaptures.resolve(mvc, tokenB, captureB, "{\"action\":\"UPDATE\",\"recipeId\":\"" + recipeA + "\"}");
        assertThat(update).hasStatus(409);
        assertThat(TestAuth.body(update)).contains("recipe_gone");
    }

    @Test
    void tagCatalogsAreSeparate() {
        assertThat(mvc.post().uri("/v1/tags/categories").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"iso-" + sfx + "\",\"title\":\"Izolacja\"}"))
                .hasStatusOk();
        assertThat(mvc.post().uri("/v1/tags/categories/iso-{s}/tags", sfx).header(HttpHeaders.AUTHORIZATION,
                TestAuth.bearer(tokenA)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"title\":\"A\"}"))
                .hasStatusOk();
        assertThat(TestAuth.body(get(tokenB, "/v1/tags"))).doesNotContain("iso-" + sfx);

        String category = "/v1/tags/categories/iso-" + sfx;
        assertThat(mvc.patch().uri(category).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Przejęta\"}")).hasStatus(404);
        assertThat(mvc.post().uri(category + "/tags").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"b\",\"title\":\"B\"}")).hasStatus(404);
        assertThat(mvc.patch().uri(category + "/tags/a").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Przejęty\"}")).hasStatus(404);
        assertThat(mvc.delete().uri(category + "/tags/a").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB)))
                .hasStatus(404);
        assertThat(mvc.delete().uri(category).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB))).hasStatus(404);

        // B cannot choose A's tag for its recipe ...
        UUID recipeB = TestCaptures.newRecipe(mvc, tokenB, worker, TestCaptures.webCapture(UUID.randomUUID(),
                "https://iso-" + sfx + ".pl/tagi", "Tagi " + sfx));
        var foreign = mvc.put().uri("/v1/recipes/{id}/tags", recipeB).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"iso-" + sfx + ":a\"]}").exchange();
        assertThat(foreign).hasStatus(400);
        assertThat(TestAuth.body(foreign)).contains("unknown_tag");

        // ... but may have a category with the same name, and A's stays untouched
        assertThat(mvc.post().uri("/v1/tags/categories").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"iso-" + sfx + "\",\"title\":\"Inna\"}"))
                .hasStatusOk();
        assertThat(mvc.put().uri("/v1/recipes/{id}/tags", recipeA).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"iso-" + sfx + ":a\"]}")).hasStatusOk();
        String catalogA = TestAuth.body(get(tokenA, "/v1/tags"));
        assertThat(catalogA).contains("Izolacja").doesNotContain("Inna");
        assertThat(TestAuth.body(get(tokenA, "/v1/recipes/{id}", recipeA))).contains("iso-" + sfx + ":a");
    }

    @Test
    void blobsOfAnotherHouseholdAreNotFound() throws IOException {
        assertThat(get(tokenA, "/v1/blobs/{sha}", attachmentA)).hasStatusOk();
        assertThat(get(tokenB, "/v1/blobs/{sha}", attachmentA)).hasStatus(404);
        assertThat(get(tokenA, "/v1/blobs/{sha}", photoA)).hasStatusOk();
        assertThat(get(tokenB, "/v1/blobs/{sha}", photoA)).hasStatus(404);

        // B's recipe cannot name A's photo ...
        UUID recipeB = TestCaptures.newRecipe(mvc, tokenB, worker, TestCaptures.webCapture(UUID.randomUUID(),
                "https://iso-" + sfx + ".pl/foto", "Foto " + sfx));
        var foreign = saveWithImage(tokenB, recipeB, "blob:" + photoA);
        assertThat(foreign).hasStatus(400);
        assertThat(TestAuth.body(foreign)).contains("unknown_photo");

        // ... until B uploads the same picture itself: one blob, a photo row per household
        assertThat(uploadPhoto(tokenB, photoContent)).hasStatus(201);
        assertThat(get(tokenB, "/v1/blobs/{sha}", photoA)).hasStatusOk();
        assertThat(saveWithImage(tokenB, recipeB, "blob:" + photoA)).hasStatus(201);
        assertThat(jdbc.sql("SELECT count(*) FROM user_photo WHERE blob_sha256 = :sha").param("sha", photoA)
                .query(Long.class).single()).isEqualTo(2);
    }

    private MvcTestResult get(String token, String uri, Object... vars) {
        return mvc.get().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
    }

    private MvcTestResult uploadPhoto(String token, byte[] content) {
        return mvc.post().uri("/v1/blobs").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .multipart().part(new MockPart("image", "photo.png", content, MediaType.IMAGE_PNG)).exchange();
    }

    private MvcTestResult saveWithImage(String token, UUID recipeId, String image) {
        String lock = JsonPath.read(TestAuth.body(mvc.post().uri("/v1/recipes/{id}/lock", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange()), "$.lockId");
        String view = TestAuth.body(get(token, "/v1/recipes/{id}", recipeId));
        ObjectNode recipe = (ObjectNode) JSON.readTree(view).get("recipe");
        recipe.putArray("images").add(image);
        var result = mvc.post().uri("/v1/recipes/{id}/revisions", recipeId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token))
                .header("Lock-Id", lock)
                .header(HttpHeaders.IF_MATCH, "\"" + JsonPath.read(view, "$.headRevisionId") + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(recipe.toString())
                .exchange();
        if (result.getResponse().getStatus() != 201) {
            mvc.delete().uri("/v1/recipes/{id}/lock/{lock}", recipeId, lock)
                    .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)).exchange();
        }
        return result;
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color((int) (Math.random() * 0xFFFFFF)));
        g.fillRect(0, 0, 400, 300);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
