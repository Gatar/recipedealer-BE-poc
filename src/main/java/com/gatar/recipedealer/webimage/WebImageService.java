package com.gatar.recipedealer.webimage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.blob.BlobRef;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Images;
import com.gatar.recipedealer.recipe.RecipeRepository;

/**
 * Server copies of recipe photos from other sites, so a recipe keeps its photo (and the backup has it) when the site
 * changes or disappears. The photo is a live recipe head's {@code images[0]}; the copy becomes the recipe's
 * {@code thumbnailBlob}. Failed downloads are retried with growing delays, then given up.
 */
@Service
public class WebImageService {

    private static final Logger log = LoggerFactory.getLogger(WebImageService.class);

    private final JdbcClient jdbc;
    private final WebImageDownloader downloader;
    private final RecipeRepository recipes;
    private final WebImageProperties properties;
    private final TransactionTemplate tx;
    private final BlobStore blobs;

    public WebImageService(JdbcClient jdbc, WebImageDownloader downloader, RecipeRepository recipes,
                           WebImageProperties properties, TransactionTemplate tx, BlobStore blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.downloader = downloader;
        this.recipes = recipes;
        this.properties = properties;
        this.tx = tx;
    }

    /** Photos of live recipes without a copy whose download is due. */
    public List<String> due() {
        return jdbc.sql("""
                        SELECT DISTINCT trim(h.content->'images'->>0) AS url
                        FROM recipe r
                        JOIN recipe_revision h ON h.id = r.head_revision_id
                        LEFT JOIN web_image w ON w.url = trim(h.content->'images'->>0)
                        WHERE r.deleted_at IS NULL AND r.merged_into IS NULL
                          AND h.content->'images'->>0 ~* '^\\s*https?://'
                          AND (w.url IS NULL OR (w.blob_sha256 IS NULL AND w.next_attempt_at <= now()))
                        LIMIT :limit""")
                .param("limit", properties.batchSize())
                .query(String.class)
                .list();
    }

    /** Downloads one batch of due photos; returns how many were tried. */
    public int drain() {
        List<String> urls = due();
        urls.forEach(this::fetch);
        return urls.size();
    }

    /**
     * Downloads one photo, stores it as WebP ({@link Images#photo}; as it came when the server cannot decode it, e.g.
     * AVIF) and sets it on the recipes that show it, or records the failure.
     */
    public void fetch(String url) {
        Stored image;
        try {
            WebImageDownloader.Image download = downloader.download(url);
            image = store(download);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            boolean permanent = e instanceof WebImageDownloader.PermanentFailure;
            recordFailure(url, e.getMessage() == null ? e.toString() : e.getMessage(), permanent);
            log.warn("Could not download photo {}: {}{}", url, e, permanent ? " (given up)" : "");
            return;
        }
        Integer updated = tx.execute(status -> {
            jdbc.sql("INSERT INTO blob (sha256, size_bytes) VALUES (:sha256, :size) ON CONFLICT (sha256) DO NOTHING")
                    .param("sha256", image.blob().sha256())
                    .param("size", image.blob().sizeBytes())
                    .update();
            jdbc.sql("""
                            INSERT INTO web_image (url, blob_sha256, media_type, attempts) VALUES (:url, :sha256, :type, 1)
                            ON CONFLICT (url) DO UPDATE
                                SET blob_sha256 = EXCLUDED.blob_sha256, media_type = EXCLUDED.media_type,
                                    attempts = web_image.attempts + 1, next_attempt_at = NULL, error = NULL,
                                    updated_at = now()""")
                    .param("url", url)
                    .param("sha256", image.blob().sha256())
                    .param("type", image.mediaType())
                    .update();
            return recipes.setWebImageThumbnail(url, image.blob().sha256());
        });
        log.info("Downloaded photo {} ({}, {} bytes) for {} recipe(s)", url, image.mediaType(), image.blob().sizeBytes(), updated);
    }

    private record Stored(String mediaType, BlobRef blob) {
    }

    private Stored store(WebImageDownloader.Image download) throws IOException {
        BufferedImage decoded;
        try {
            decoded = Images.read(download.bytes());
        } catch (IOException e) {
            decoded = null;
        }
        if (decoded == null) {
            log.info("Photo of type {} cannot be decoded, stored as it came", download.mediaType());
            return new Stored(download.mediaType(), blobs.put(new ByteArrayInputStream(download.bytes())));
        }
        return new Stored(Images.WEBP, blobs.put(new ByteArrayInputStream(Images.photo(decoded))));
    }

    /** Next attempt after base × 4^(attempts-1): 1 h, 4 h, 16 h, …; none after a permanent failure or the last attempt. */
    private void recordFailure(String url, String error, boolean permanent) {
        jdbc.sql("""
                        INSERT INTO web_image AS w (url, attempts, next_attempt_at, error)
                        VALUES (:url, 1, CASE WHEN :giveUp OR :maxAttempts <= 1 THEN NULL
                                              ELSE now() + make_interval(secs => :baseSeconds) END, :error)
                        ON CONFLICT (url) DO UPDATE
                            SET attempts = w.attempts + 1, error = EXCLUDED.error, updated_at = now(),
                                next_attempt_at = CASE WHEN :giveUp OR w.attempts + 1 >= :maxAttempts THEN NULL
                                                       ELSE now() + make_interval(secs => :baseSeconds * power(4, w.attempts)) END""")
                .param("url", url)
                .param("giveUp", permanent)
                .param("maxAttempts", properties.maxAttempts())
                .param("baseSeconds", (double) properties.retryBase().toSeconds())
                .param("error", error.length() > 500 ? error.substring(0, 500) : error)
                .update();
    }
}
