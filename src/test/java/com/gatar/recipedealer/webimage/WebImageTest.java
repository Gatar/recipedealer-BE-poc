package com.gatar.recipedealer.webimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.util.unit.DataSize;

import com.gatar.recipedealer.IntegrationTest;
import com.gatar.recipedealer.TestAuth;
import com.gatar.recipedealer.TestCaptures;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Hashing;
import com.gatar.recipedealer.processing.JobWorker;
import com.sun.net.httpserver.HttpServer;

@IntegrationTest
class WebImageTest {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3, 4, 5};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 9, 8, 7};

    static HttpServer server;
    static String base;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Autowired
    WebImageService images;

    @Autowired
    BlobStore blobs;

    String token;
    String suffix;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String type = "image/jpeg";
            byte[] body = JPEG;
            if (path.startsWith("/png/")) {
                type = "image/png";
                body = PNG;
            } else if (path.startsWith("/missing/")) {
                status = 404;
            } else if (path.startsWith("/broken/")) {
                status = 503;
            } else if (path.startsWith("/page/")) {
                type = "text/html; charset=utf-8";
                body = "<html></html>".getBytes();
            } else if (path.startsWith("/moved/")) {
                status = 302;
                exchange.getResponseHeaders().add("Location", "/png/target.png");
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, status == 302 ? -1 : body.length);
            if (status != 302) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        token = TestAuth.enroll(mvc, "Photos", "phone");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        worker.drain();
    }

    private String photo(String kind) {
        return base + "/" + kind + "/" + suffix + "-" + UUID.randomUUID() + ".jpg";
    }

    private UUID recipeWithPhoto(String imageUrl) {
        String url = "https://www.photos-" + suffix + ".pl/przepis/" + UUID.randomUUID() + "/";
        return TestCaptures.newRecipe(mvc, token, worker, TestCaptures.webCapture(UUID.randomUUID(), c -> {
            c.withObject("source").put("url", url);
            c.withObject("raw").withObject("web").put("canonicalUrl", url);
            c.withObject("recipe").put("title", "Zdjęcie " + suffix);
            c.withObject("recipe").putArray("images").add(imageUrl);
        }));
    }

    private String thumbnail(UUID recipeId) {
        return jdbc.sql("SELECT thumbnail_blob FROM recipe WHERE id = :id").param("id", recipeId)
                .query(String.class).optional().orElse(null);
    }

    private Map<String, Object> row(String url) {
        return jdbc.sql("SELECT attempts, next_attempt_at, blob_sha256, error FROM web_image WHERE url = :url")
                .param("url", url).query().singleRow();
    }

    @Test
    void photoIsDownloadedAndBecomesTheThumbnail() {
        String url = photo("jpeg");
        UUID recipeId = recipeWithPhoto(url);
        assertThat(thumbnail(recipeId)).isNull();
        assertThat(images.due()).contains(url);

        images.fetch(url);

        String sha256 = Hashing.sha256Hex(JPEG);
        assertThat(thumbnail(recipeId)).isEqualTo(sha256);
        assertThat(blobs.exists(sha256)).isTrue();
        assertThat(images.due()).doesNotContain(url);
        assertThat(mvc.get().uri("/v1/recipes/{id}", recipeId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatusOk().bodyJson().extractingPath("$.thumbnailBlob").isEqualTo(sha256);
    }

    @Test
    void blobIsServedWithTheImageType() {
        String url = photo("png");
        recipeWithPhoto(url);
        images.fetch(url);

        assertThat(mvc.get().uri("/v1/blobs/{sha}", Hashing.sha256Hex(PNG)).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(token)))
                .hasStatusOk().hasContentType("image/png");
    }

    @Test
    void aPhotoAlreadyDownloadedIsUsedAtOnce() {
        String url = photo("jpeg");
        recipeWithPhoto(url);
        images.fetch(url);

        UUID second = recipeWithPhoto(url);

        assertThat(thumbnail(second)).isEqualTo(Hashing.sha256Hex(JPEG));
    }

    @Test
    void redirectsAreFollowed() {
        String url = photo("moved");
        UUID recipeId = recipeWithPhoto(url);

        images.fetch(url);

        assertThat(thumbnail(recipeId)).isEqualTo(Hashing.sha256Hex(PNG));
    }

    @Test
    void missingPhotoOrPageIsGivenUp() {
        for (String url : new String[] {photo("missing"), photo("page")}) {
            UUID recipeId = recipeWithPhoto(url);
            images.fetch(url);

            assertThat(thumbnail(recipeId)).isNull();
            assertThat(row(url)).containsEntry("attempts", 1).containsEntry("next_attempt_at", null)
                    .containsEntry("blob_sha256", null);
            assertThat(images.due()).doesNotContain(url);
        }
    }

    @Test
    void serverErrorIsRetriedLater() {
        String url = photo("broken");
        recipeWithPhoto(url);

        images.fetch(url);
        images.fetch(url);

        assertThat(row(url)).containsEntry("attempts", 2).containsEntry("error", "HTTP 503");
        assertThat(row(url).get("next_attempt_at")).isNotNull();
        assertThat(images.due()).doesNotContain(url);
        assertThat(jdbc.sql("SELECT next_attempt_at > now() + interval '3 hours' FROM web_image WHERE url = :url")
                .param("url", url).query(Boolean.class).single()).isTrue();
    }

    @Test
    void privateAddressesAreRefusedByDefault() throws Exception {
        var strict = new WebImageDownloader(blobs, new WebImageProperties(null, 20, DataSize.ofMegabytes(10),
                java.time.Duration.ofSeconds(5), 5, java.time.Duration.ofHours(1), false));

        assertThatThrownBy(() -> strict.download(photo("jpeg")))
                .isInstanceOf(WebImageDownloader.PermanentFailure.class).hasMessageContaining("address not allowed");

        for (String address : new String[] {"127.0.0.1", "10.1.2.3", "172.18.0.2", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1"}) {
            assertThat(WebImageDownloader.isPrivate(InetAddress.getByName(address))).as(address).isTrue();
        }
        assertThat(WebImageDownloader.isPrivate(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(WebImageDownloader.isPrivate(InetAddress.getByName("2a01:4f8::1"))).isFalse();
    }

    @Test
    void urlsArePercentEncodedAndOnlyHttpIsAccepted() throws Exception {
        assertThat(WebImageDownloader.toUri(" https://example.pl/zdjęcia/a b.jpg?x=1 ").toString())
                .isEqualTo("https://example.pl/zdj%C4%99cia/a%20b.jpg?x=1");
        assertThat(WebImageDownloader.redirect(WebImageDownloader.toUri("https://example.pl/a/b.jpg"), "../c.jpg").toString())
                .isEqualTo("https://example.pl/c.jpg");
        assertThatThrownBy(() -> WebImageDownloader.toUri("ftp://example.pl/a.jpg"))
                .isInstanceOf(WebImageDownloader.PermanentFailure.class);
        assertThatThrownBy(() -> WebImageDownloader.redirect(WebImageDownloader.toUri("https://example.pl/"), "file:///etc/passwd"))
                .isInstanceOf(WebImageDownloader.PermanentFailure.class);
    }
}
