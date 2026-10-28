package com.gatar.recipedealer.llm;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.blob.BlobRef;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Images;
import com.gatar.recipedealer.processing.Capture;
import com.gatar.recipedealer.processing.DishPhotoFinding;

/**
 * Cuts the dish photo the model pointed at out of the original attachment (not out of the downscaled copy the model
 * saw) and stores it as a JPEG blob. A box that maps to no decodable original, or whose shorter edge is below
 * {@link Images#PHOTO_MIN_EDGE}, counts as no photo.
 */
@Component
public class DishPhotoCropper {

    private static final Logger log = LoggerFactory.getLogger(DishPhotoCropper.class);

    private final BlobStore blobs;
    private final LlmInputBuilder inputs;

    public DishPhotoCropper(BlobStore blobs, LlmInputBuilder inputs) {
        this.blobs = blobs;
        this.inputs = inputs;
    }

    /** @return null when no image was sent (nothing to judge) */
    public DishPhotoFinding crop(Capture capture, List<LlmInputBuilder.SentImage> images, RecipePrompt.DishPhoto box)
            throws IOException {
        if (images.isEmpty()) {
            return null;
        }
        if (box == null) {
            return DishPhotoFinding.NONE;
        }
        LlmInputBuilder.SentImage sent = images.stream().filter(image -> image.number() == box.image()).findFirst().orElse(null);
        if (sent == null || sent.region() == null) {
            log.info("Capture {}: dish photo in image {} cannot be cut out", capture.id(), box.image());
            return DishPhotoFinding.NONE;
        }
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(inputs.read(sent.attachment())));
        if (original == null) {
            return DishPhotoFinding.NONE;
        }
        LlmInputBuilder.Region region = sent.region();
        int left = clamp(region.x() + (int) Math.round(region.width() * box.left() / 1000.0), original.getWidth());
        int right = clamp(region.x() + (int) Math.round(region.width() * box.right() / 1000.0), original.getWidth());
        int top = clamp(region.y() + (int) Math.round(region.height() * box.top() / 1000.0), original.getHeight());
        int bottom = clamp(region.y() + (int) Math.round(region.height() * box.bottom() / 1000.0), original.getHeight());
        int width = right - left;
        int height = bottom - top;
        if (Math.min(width, height) < Images.PHOTO_MIN_EDGE) {
            log.info("Capture {}: dish photo {}x{} in {} is too small, ignored", capture.id(), width, height,
                    sent.attachment().name());
            return DishPhotoFinding.NONE;
        }
        byte[] jpeg = Images.jpeg(Images.fit(original.getSubimage(left, top, width, height), Images.PHOTO_MAX_EDGE));
        BlobRef blob = blobs.put(new ByteArrayInputStream(jpeg));
        return new DishPhotoFinding(new DishPhotoFinding.Photo(sent.attachment().name(), blob.sha256(), blob.sizeBytes(),
                left, top, width, height));
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }
}
