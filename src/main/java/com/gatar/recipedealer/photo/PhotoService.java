package com.gatar.recipedealer.photo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.blob.BlobJanitor;
import com.gatar.recipedealer.blob.BlobRef;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Images;
import com.gatar.recipedealer.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recipe photos. Uploads from the phone are re-encoded like every stored photo ({@link Images#photo}: WebP of at most
 * {@link Images#PHOTO_MAX_EDGE} px) and stored content-addressed; a recipe shows one through
 * {@code images[0] = "blob:<sha256>"}.
 * <p>
 * Only current photos are kept (user, 2026-11-27): {@link #sweep} deletes every stored photo no live recipe shows and
 * no capture still waiting for a decision needs: a replaced upload, the web copy and the dish or PDF photo a user photo
 * replaced, the photos of older captures. Restoring an older revision therefore brings no photo back.
 */
@Service
public class PhotoService {

    public static final String REF_PREFIX = "blob:";
    private static final Pattern REF = Pattern.compile("blob:[0-9a-f]{64}");
    /** Formats {@link #convertToWebp} re-encodes (ImageIO reads them). */
    private static final List<String> CONVERTIBLE = List.of("image/jpeg", "image/png", "image/gif", "image/bmp");

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final BlobStore blobs;
    private final BlobJanitor janitor;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Duration grace;
    /** Photos that could not be converted, not tried again until a restart. */
    private final Set<String> unconvertible = ConcurrentHashMap.newKeySet();

    public PhotoService(BlobStore blobs, BlobJanitor janitor, JdbcClient jdbc, TransactionTemplate tx,
                        @Value("${recipedealer.photos.unused-grace:7d}") Duration grace) {
        this.blobs = blobs;
        this.janitor = janitor;
        this.jdbc = jdbc;
        this.tx = tx;
        this.grace = grace;
    }

    /** @param ref {@code blob:<sha256>}, the value for {@code images[]} */
    public record Photo(String blob, String ref, int width, int height, long sizeBytes) {
    }

    @Transactional
    public Photo upload(byte[] content, DevicePrincipal device) throws IOException {
        BufferedImage source;
        try {
            source = Images.read(content);
        } catch (IOException e) {
            throw invalid("the image cannot be read: " + e.getMessage());
        }
        if (source == null) {
            throw invalid("not an image (JPEG, PNG or WebP)");
        }
        BufferedImage photo = Images.fit(source, Images.PHOTO_MAX_EDGE);
        BlobRef blob = blobs.put(new ByteArrayInputStream(Images.webp(photo)));
        jdbc.sql("INSERT INTO blob (sha256, size_bytes) VALUES (:sha256, :size) ON CONFLICT (sha256) DO NOTHING")
                .param("sha256", blob.sha256()).param("size", blob.sizeBytes()).update();
        jdbc.sql("""
                        INSERT INTO user_photo (household_id, blob_sha256, width, height, device_id, media_type)
                        VALUES (:household, :sha256, :width, :height, :device, :mediaType)
                        ON CONFLICT (household_id, blob_sha256) DO NOTHING""")
                .param("household", device.householdId())
                .param("sha256", blob.sha256()).param("width", photo.getWidth()).param("height", photo.getHeight())
                .param("device", device.deviceId())
                .param("mediaType", Images.WEBP)
                .update();
        log.info("Stored photo {} ({}x{}, {} bytes, uploaded {} bytes) from device {}", blob.sha256(), photo.getWidth(),
                photo.getHeight(), blob.sizeBytes(), content.length, device.deviceId());
        return new Photo(blob.sha256(), REF_PREFIX + blob.sha256(), photo.getWidth(), photo.getHeight(), blob.sizeBytes());
    }

    /** Images of a live recipe head in the household ({@code %s}: the JSON array to look for). */
    private static final String SHOWN_BY_HEAD = """
            EXISTS (SELECT 1 FROM recipe r JOIN recipe_revision h ON h.id = r.head_revision_id
                    WHERE r.household_id = u.household_id AND r.deleted_at IS NULL AND r.merged_into IS NULL
                      AND h.content->'images' @> %s)""";

    /**
     * Deletes the stored photos that are not current (files after the commit); returns the deleted blobs.
     * <ul>
     * <li>Uploads no live head shows and no recipe has as its thumbnail, unless a capture that is not done yet names
     * them: at once when a revision or capture named them (replaced), after the grace period when nothing ever did (a
     * photo picked in the editor and then cancelled; the grace covers a capture still waiting on the phone).</li>
     * <li>Web copies of photos no live head shows.</li>
     * <li>Dish and PDF photos of done captures that are no recipe's thumbnail; the row stays (blob NULL), so they are
     * neither searched nor cut out again.</li>
     * </ul>
     */
    public List<String> sweep() {
        List<String> deleted = tx.execute(status -> {
            List<String> candidates = new ArrayList<>();
            String array = "jsonb_build_array('blob:' || u.blob_sha256)";
            record Upload(UUID household, String sha256) {
            }
            List<Upload> uploads = jdbc.sql(("""
                            SELECT u.household_id, u.blob_sha256 FROM user_photo u
                            WHERE NOT %s
                              AND NOT EXISTS (SELECT 1 FROM recipe r
                                              WHERE r.household_id = u.household_id AND r.thumbnail_blob = u.blob_sha256)
                              AND NOT EXISTS (SELECT 1 FROM capture c JOIN capture_state s ON s.capture_id = c.id
                                              WHERE c.household_id = u.household_id AND s.status <> 'done'
                                                AND c.body->'recipe'->'images' @> %s)
                              AND (u.created_at < now() - make_interval(secs => :seconds)
                                   OR EXISTS (SELECT 1 FROM recipe_revision v JOIN recipe r ON r.id = v.recipe_id
                                              WHERE r.household_id = u.household_id AND v.content->'images' @> %s)
                                   OR EXISTS (SELECT 1 FROM capture c
                                              WHERE c.household_id = u.household_id
                                                AND c.body->'recipe'->'images' @> %s))
                            FOR UPDATE OF u SKIP LOCKED""").formatted(SHOWN_BY_HEAD.formatted(array), array, array, array))
                    .param("seconds", (double) grace.toSeconds())
                    .query((rs, n) -> new Upload(rs.getObject("household_id", UUID.class), rs.getString("blob_sha256")))
                    .list();
            for (Upload upload : uploads) {
                jdbc.sql("DELETE FROM user_photo WHERE household_id = :household AND blob_sha256 = :sha256")
                        .param("household", upload.household()).param("sha256", upload.sha256()).update();
                candidates.add(upload.sha256());
            }
            candidates.addAll(jdbc.sql("""
                            DELETE FROM web_image w
                            WHERE NOT EXISTS (SELECT 1 FROM recipe r JOIN recipe_revision h ON h.id = r.head_revision_id
                                              WHERE r.deleted_at IS NULL AND r.merged_into IS NULL
                                                AND trim(h.content->'images'->>0) = w.url)
                              AND (w.blob_sha256 IS NULL
                                   OR NOT EXISTS (SELECT 1 FROM recipe r WHERE r.thumbnail_blob = w.blob_sha256))
                            RETURNING w.blob_sha256""").query(String.class).list());
            candidates.addAll(jdbc.sql("""
                            WITH gone AS (
                                SELECT d.run_id, d.blob_sha256 FROM dish_photo d
                                JOIN capture_state s ON s.capture_id = d.capture_id AND s.status = 'done'
                                WHERE d.blob_sha256 IS NOT NULL
                                  AND NOT EXISTS (SELECT 1 FROM recipe r WHERE r.thumbnail_blob = d.blob_sha256)
                                FOR UPDATE OF d SKIP LOCKED)
                            UPDATE dish_photo d SET blob_sha256 = NULL, attachment_name = NULL, media_type = NULL
                            FROM gone WHERE d.run_id = gone.run_id
                            RETURNING gone.blob_sha256""").query(String.class).list());
            candidates.addAll(jdbc.sql("""
                            WITH gone AS (
                                SELECT p.capture_id, p.attachment_name, p.blob_sha256 FROM pdf_image p
                                JOIN capture_state s ON s.capture_id = p.capture_id AND s.status = 'done'
                                WHERE p.blob_sha256 IS NOT NULL
                                  AND NOT EXISTS (SELECT 1 FROM recipe r WHERE r.thumbnail_blob = p.blob_sha256)
                                FOR UPDATE OF p SKIP LOCKED)
                            UPDATE pdf_image p SET blob_sha256 = NULL, media_type = NULL, page = NULL, width = NULL,
                                                   height = NULL
                            FROM gone WHERE p.capture_id = gone.capture_id AND p.attachment_name = gone.attachment_name
                            RETURNING gone.blob_sha256""").query(String.class).list());
            // the same content can also be an attachment, another household's photo or a thumbnail: then only the
            // photo row goes
            return janitor.deleteRows(candidates);
        });
        janitor.deleteFiles(deleted);
        if (!deleted.isEmpty()) {
            log.info("Deleted {} photo(s) that are not current", deleted.size());
        }
        return deleted;
    }

    /** A stored photo in an older format: which row holds it. */
    private record Legacy(String kind, String key, String sha256) {
    }

    /**
     * Re-encodes up to {@code limit} stored photos from before V24 (JPEG, PNG, … of up to 1600 px) as WebP: dish, PDF
     * and web photos; their recipes get the new thumbnail (and come again in the sync feed). Uploads stay as they are:
     * revisions name them. Returns how many were converted.
     */
    public int convertToWebp(int limit) {
        List<Legacy> legacy = jdbc.sql("""
                        (SELECT 'dish' AS kind, run_id::text AS key, blob_sha256 FROM dish_photo
                         WHERE media_type = 'image/jpeg' LIMIT :limit)
                        UNION ALL
                        (SELECT 'pdf', capture_id::text || '/' || attachment_name, blob_sha256 FROM pdf_image
                         WHERE media_type = 'image/jpeg' LIMIT :limit)
                        UNION ALL
                        (SELECT 'web', url, blob_sha256 FROM web_image
                         WHERE blob_sha256 IS NOT NULL AND media_type = ANY (:types) LIMIT :limit)""")
                .param("limit", limit + unconvertible.size())
                .param("types", CONVERTIBLE.toArray(String[]::new))
                .query((rs, n) -> new Legacy(rs.getString("kind"), rs.getString("key"), rs.getString("blob_sha256")))
                .list().stream()
                .filter(l -> !unconvertible.contains(l.kind() + ":" + l.key()))
                .limit(limit)
                .toList();
        int converted = 0;
        for (Legacy photo : legacy) {
            try {
                converted += convert(photo) ? 1 : 0;
            } catch (IOException | RuntimeException e) {
                unconvertible.add(photo.kind() + ":" + photo.key());
                log.warn("Could not convert {} photo {} ({}) to WebP: {}", photo.kind(), photo.key(), photo.sha256(), e.toString());
            }
        }
        if (converted > 0) {
            log.info("Converted {} photo(s) to WebP", converted);
        }
        return converted;
    }

    private boolean convert(Legacy photo) throws IOException {
        byte[] original;
        try (InputStream in = blobs.open(photo.sha256())) {
            original = in.readAllBytes();
        }
        BufferedImage decoded = Images.read(original);
        if (decoded == null) {
            throw new IOException("cannot be decoded");
        }
        BufferedImage fitted = Images.fit(decoded, Images.PHOTO_MAX_EDGE);
        BlobRef webp = blobs.put(new ByteArrayInputStream(Images.webp(fitted)));
        List<String> unused = tx.execute(status -> {
            jdbc.sql("INSERT INTO blob (sha256, size_bytes) VALUES (:sha256, :size) ON CONFLICT (sha256) DO NOTHING")
                    .param("sha256", webp.sha256()).param("size", webp.sizeBytes()).update();
            int updated = switch (photo.kind()) {
                case "dish" -> jdbc.sql("""
                                UPDATE dish_photo SET blob_sha256 = :new, media_type = :type
                                WHERE run_id = CAST(:key AS uuid) AND blob_sha256 = :old""")
                        .param("key", photo.key()).param("new", webp.sha256()).param("old", photo.sha256())
                        .param("type", Images.WEBP).update();
                case "pdf" -> jdbc.sql("""
                                UPDATE pdf_image SET blob_sha256 = :new, media_type = :type, width = :width, height = :height
                                WHERE capture_id = CAST(split_part(:key, '/', 1) AS uuid)
                                  AND attachment_name = substr(:key, 38) AND blob_sha256 = :old""")
                        .param("key", photo.key()).param("new", webp.sha256()).param("old", photo.sha256())
                        .param("type", Images.WEBP).param("width", fitted.getWidth()).param("height", fitted.getHeight())
                        .update();
                default -> jdbc.sql("""
                                UPDATE web_image SET blob_sha256 = :new, media_type = :type, updated_at = now()
                                WHERE url = :key AND blob_sha256 = :old""")
                        .param("key", photo.key()).param("new", webp.sha256()).param("old", photo.sha256())
                        .param("type", Images.WEBP).update();
            };
            if (updated == 0) {
                return null; // changed meanwhile
            }
            jdbc.sql("UPDATE recipe SET thumbnail_blob = :new, updated_at = now() WHERE thumbnail_blob = :old")
                    .param("new", webp.sha256()).param("old", photo.sha256()).update();
            return janitor.deleteRows(List.of(photo.sha256()));
        });
        if (unused == null) {
            janitor.deleteFiles(tx.execute(status -> janitor.deleteRows(List.of(webp.sha256()))));
            return false;
        }
        janitor.deleteFiles(unused);
        return true;
    }

    /**
     * The recipe as sent, without {@code blob:} photos that were deleted meanwhile but an older revision of this
     * recipe named (restoring a revision whose photo was replaced: it comes back without the photo).
     */
    public JsonNode withoutDeletedPhotos(UUID recipeId, UUID household, JsonNode recipe) {
        if (!(recipe instanceof ObjectNode object) || !(recipe.get("images") instanceof ArrayNode images)) {
            return recipe;
        }
        ArrayNode kept = images.arrayNode();
        boolean dropped = false;
        for (JsonNode image : images) {
            String value = image.isString() ? image.asString().strip() : "";
            if (REF.matcher(value).matches() && !known(household, value) && jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM recipe_revision
                                           WHERE recipe_id = :id AND content->'images' @> jsonb_build_array(CAST(:ref AS text)))""")
                    .param("id", recipeId).param("ref", value).query(Boolean.class).single()) {
                dropped = true;
            } else {
                kept.add(image);
            }
        }
        if (!dropped) {
            return recipe;
        }
        ObjectNode copy = object.deepCopy();
        copy.set("images", kept);
        return copy;
    }

    /** 400 {@code unknown_photo} if a {@code blob:} entry of {@code images} is not a photo uploaded in the household. */
    public void requireKnown(UUID household, JsonNode recipe) {
        JsonNode images = recipe.path("images");
        for (JsonNode image : images) {
            String value = image.isString() ? image.asString().strip() : "";
            if (!value.startsWith(REF_PREFIX)) {
                continue;
            }
            if (!REF.matcher(value).matches() || !known(household, value)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_photo", "no uploaded photo " + value);
            }
        }
    }

    private boolean known(UUID household, String ref) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_photo WHERE household_id = :household AND blob_sha256 = :sha256)")
                .param("household", household)
                .param("sha256", ref.substring(REF_PREFIX.length())).query(Boolean.class).single();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_image", message);
    }
}
