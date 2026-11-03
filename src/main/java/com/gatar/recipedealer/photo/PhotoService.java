package com.gatar.recipedealer.photo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
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

    public PhotoService(BlobStore blobs, JdbcClient jdbc) {
        this.blobs = blobs;
        this.jdbc = jdbc;
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
                        INSERT INTO user_photo (blob_sha256, width, height, device_id) VALUES (:sha256, :width, :height, :device)
                        ON CONFLICT (blob_sha256) DO NOTHING""")
                .param("sha256", blob.sha256()).param("width", photo.getWidth()).param("height", photo.getHeight())
                .param("device", device.deviceId())
                .update();
        log.info("Stored photo {} ({}x{}, {} bytes, uploaded {} bytes) from device {}", blob.sha256(), photo.getWidth(),
                photo.getHeight(), blob.sizeBytes(), content.length, device.deviceId());
        return new Photo(blob.sha256(), REF_PREFIX + blob.sha256(), photo.getWidth(), photo.getHeight(), blob.sizeBytes());
    }

    /** 400 {@code unknown_photo} if a {@code blob:} entry of {@code images} is not an uploaded photo. */
    public void requireKnown(JsonNode recipe) {
        JsonNode images = recipe.path("images");
        for (JsonNode image : images) {
            String value = image.isString() ? image.asString().strip() : "";
            if (!value.startsWith(REF_PREFIX)) {
                continue;
            }
            boolean known = REF.matcher(value).matches() && jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_photo WHERE blob_sha256 = :sha256)")
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
