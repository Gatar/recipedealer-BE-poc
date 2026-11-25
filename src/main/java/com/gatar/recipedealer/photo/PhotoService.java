package com.gatar.recipedealer.photo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.blob.BlobRef;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Images;
import com.gatar.recipedealer.web.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Recipe photos from the phone: whatever is uploaded is re-encoded as JPEG of at most {@link Images#PHOTO_MAX_EDGE}
 * px on the longer edge (a few hundred KB instead of a camera's several MB) and stored content-addressed. A recipe
 * shows it through {@code images[0] = "blob:<sha256>"}.
 */
@Service
public class PhotoService {

    public static final String REF_PREFIX = "blob:";
    private static final Pattern REF = Pattern.compile("blob:[0-9a-f]{64}");
    /** Larger images are decoded subsampled, so a huge upload never needs more memory than this many pixels. */
    private static final long MAX_DECODED_PIXELS = 4000L * 4000L;

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final BlobStore blobs;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public PhotoService(BlobStore blobs, JdbcClient jdbc, TransactionTemplate tx) {
        this.blobs = blobs;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** @param ref {@code blob:<sha256>}, the value for {@code images[]} */
    public record Photo(String blob, String ref, int width, int height, long sizeBytes) {
    }

    @Transactional
    public Photo upload(byte[] content, DevicePrincipal device) throws IOException {
        BufferedImage source = decode(content);
        BufferedImage photo = Images.fit(source, Images.PHOTO_MAX_EDGE);
        byte[] jpeg = Images.jpeg(photo);
        BlobRef blob = blobs.put(new ByteArrayInputStream(jpeg));
        jdbc.sql("INSERT INTO blob (sha256, size_bytes) VALUES (:sha256, :size) ON CONFLICT (sha256) DO NOTHING")
                .param("sha256", blob.sha256()).param("size", blob.sizeBytes()).update();
        jdbc.sql("""
                        INSERT INTO user_photo (household_id, blob_sha256, width, height, device_id)
                        VALUES (:household, :sha256, :width, :height, :device)
                        ON CONFLICT (household_id, blob_sha256) DO NOTHING""")
                .param("household", device.householdId())
                .param("sha256", blob.sha256()).param("width", photo.getWidth()).param("height", photo.getHeight())
                .param("device", device.deviceId())
                .update();
        log.info("Stored photo {} ({}x{}, {} bytes, uploaded {} bytes) from device {}", blob.sha256(), photo.getWidth(),
                photo.getHeight(), blob.sizeBytes(), content.length, device.deviceId());
        return new Photo(blob.sha256(), REF_PREFIX + blob.sha256(), photo.getWidth(), photo.getHeight(), blob.sizeBytes());
    }

    /**
     * Deletes uploaded photos older than {@code olderThan} that nothing refers to: no revision (so an older version
     * can still be restored), candidate or capture of its household names it in {@code images}, and no recipe shows
     * it (e.g. a photo picked in the editor and then cancelled). The files go after the commit.
     *
     * @return the deleted blobs
     */
    public List<String> deleteUnused(Duration olderThan) {
        List<String> unused = tx.execute(status -> {
            record Row(UUID household, String sha256) {
            }
            List<Row> candidates = jdbc.sql("""
                            SELECT u.household_id, u.blob_sha256 FROM user_photo u
                            WHERE u.created_at < now() - make_interval(secs => :seconds)
                              AND NOT EXISTS (SELECT 1 FROM recipe_revision v JOIN recipe r ON r.id = v.recipe_id
                                              WHERE r.household_id = u.household_id
                                                AND v.content->'images' @> jsonb_build_array('blob:' || u.blob_sha256))
                              AND NOT EXISTS (SELECT 1 FROM recipe_candidate c JOIN processing_run pr ON pr.id = c.run_id
                                                  JOIN capture cc ON cc.id = pr.capture_id
                                              WHERE cc.household_id = u.household_id
                                                AND c.content->'images' @> jsonb_build_array('blob:' || u.blob_sha256))
                              AND NOT EXISTS (SELECT 1 FROM capture c
                                              WHERE c.household_id = u.household_id
                                                AND c.body->'recipe'->'images' @> jsonb_build_array('blob:' || u.blob_sha256))
                              AND NOT EXISTS (SELECT 1 FROM recipe r
                                              WHERE r.household_id = u.household_id AND r.thumbnail_blob = u.blob_sha256)
                            FOR UPDATE OF u SKIP LOCKED""")
                    .param("seconds", (double) olderThan.toSeconds())
                    .query((rs, n) -> new Row(rs.getObject("household_id", UUID.class), rs.getString("blob_sha256")))
                    .list();
            List<String> deleted = new ArrayList<>();
            for (Row row : candidates) {
                String sha256 = row.sha256();
                jdbc.sql("DELETE FROM user_photo WHERE household_id = :household AND blob_sha256 = :sha256")
                        .param("household", row.household()).param("sha256", sha256).update();
                // the same content can also be an attachment, another household's photo or a thumbnail: then only the
                // user_photo row goes
                if (jdbc.sql("""
                                DELETE FROM blob b WHERE b.sha256 = :sha256
                                    AND NOT EXISTS (SELECT 1 FROM user_photo WHERE blob_sha256 = b.sha256)
                                    AND NOT EXISTS (SELECT 1 FROM capture WHERE body_sha256 = b.sha256)
                                    AND NOT EXISTS (SELECT 1 FROM capture_attachment WHERE blob_sha256 = b.sha256)
                                    AND NOT EXISTS (SELECT 1 FROM pdf_image WHERE blob_sha256 = b.sha256)
                                    AND NOT EXISTS (SELECT 1 FROM dish_photo WHERE blob_sha256 = b.sha256)
                                    AND NOT EXISTS (SELECT 1 FROM web_image WHERE blob_sha256 = b.sha256)
                                    AND NOT EXISTS (SELECT 1 FROM recipe WHERE thumbnail_blob = b.sha256)""")
                        .param("sha256", sha256).update() == 1) {
                    deleted.add(sha256);
                }
            }
            return deleted;
        });
        for (String sha256 : unused) {
            try {
                blobs.delete(sha256);
            } catch (IOException e) {
                log.warn("Could not delete the file of unused photo {}", sha256, e);
            }
        }
        if (!unused.isEmpty()) {
            log.info("Deleted {} unused photo(s)", unused.size());
        }
        return unused;
    }

    /** 400 {@code unknown_photo} if a {@code blob:} entry of {@code images} is not a photo uploaded in the household. */
    public void requireKnown(UUID household, JsonNode recipe) {
        JsonNode images = recipe.path("images");
        for (JsonNode image : images) {
            String value = image.isString() ? image.asString().strip() : "";
            if (!value.startsWith(REF_PREFIX)) {
                continue;
            }
            boolean known = REF.matcher(value).matches() && jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM user_photo WHERE household_id = :household AND blob_sha256 = :sha256)""")
                    .param("household", household)
                    .param("sha256", value.substring(REF_PREFIX.length())).query(Boolean.class).single();
            if (!known) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_photo", "no uploaded photo " + value);
            }
        }
    }

    private static BufferedImage decode(byte[] content) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw invalid("not an image (JPEG or PNG)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = (int) Math.ceil(Math.sqrt((double) pixels / MAX_DECODED_PIXELS));
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return reader.read(0, param);
            } catch (IOException | RuntimeException e) {
                throw invalid("the image cannot be read: " + e.getMessage());
            } finally {
                reader.dispose();
            }
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_image", message);
    }
}
