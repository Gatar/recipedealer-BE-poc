package com.gatar.recipedealer.llm;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.blob.BlobRef;
import com.gatar.recipedealer.blob.BlobStore;
import com.gatar.recipedealer.common.Images;
import com.gatar.recipedealer.llm.LlmInputBuilder.Region;
import com.gatar.recipedealer.processing.Capture;
import com.gatar.recipedealer.processing.DishPhotoFinding;

/**
 * Cuts the dish photo the model pointed at out of the original attachment (not out of the downscaled copy the model
 * saw) and stores it upright as a JPEG blob. The box of the extraction answer is rough, so the same model takes a
 * second look ({@link RecipePrompt#DISH_PHOTO_PROMPT}) at a cut-out with a wide margin around it and gives the exact
 * edges; when that call fails or answers nonsense, the first box stays. A box that maps to no decodable original, or
 * whose shorter edge is below {@link Images#PHOTO_MIN_EDGE}, counts as no photo.
 */
@Component
public class DishPhotoCropper {

    /** Margin around the first box for the second look, as a share of the box's width and height on each side. */
    static final double SEARCH_MARGIN = 0.5;

    private static final Logger log = LoggerFactory.getLogger(DishPhotoCropper.class);

    /**
     * @param finding null when no image was sent (nothing to judge)
     * @param usage   of the second look, null when there was none
     */
    public record Result(DishPhotoFinding finding, LlmUsage usage) {
    }

    private final BlobStore blobs;
    private final LlmInputBuilder inputs;
    private final ClaudeClient client;
    private final LlmProperties properties;

    public DishPhotoCropper(BlobStore blobs, LlmInputBuilder inputs, ClaudeClient client, LlmProperties properties) {
        this.blobs = blobs;
        this.inputs = inputs;
        this.client = client;
        this.properties = properties;
    }

    public Result crop(Capture capture, List<LlmInputBuilder.SentImage> images, RecipePrompt.DishPhoto dishPhoto,
                       LlmProperties.Step step) throws IOException {
        if (images.isEmpty()) {
            return new Result(null, null);
        }
        if (dishPhoto == null) {
            return new Result(DishPhotoFinding.NONE, null);
        }
        LlmInputBuilder.SentImage sent = images.stream().filter(image -> image.number() == dishPhoto.image()).findFirst().orElse(null);
        if (sent == null || sent.region() == null) {
            log.info("Capture {}: dish photo in image {} cannot be cut out", capture.id(), dishPhoto.image());
            return new Result(DishPhotoFinding.NONE, null);
        }
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(inputs.read(sent.attachment())));
        if (original == null) {
            return new Result(DishPhotoFinding.NONE, null);
        }
        Region photo = within(sent.region(), dishPhoto.box(), original);
        Region search = expand(photo, original);

        LlmUsage usage = null;
        try {
            BufferedImage area = Images.fit(original.getSubimage(search.x(), search.y(), search.width(), search.height()),
                    properties.maxImageEdge());
            ClaudeClient.Response response = client.extract(new ClaudeClient.Request(step.model(), step.effort(),
                    RecipePrompt.DISH_PHOTO_PROMPT, RecipePrompt.dishPhotoSchema(), List.of(
                            new ClaudeClient.Image("image/jpeg", Images.jpeg(area)),
                            new ClaudeClient.Text("Find the edges of the photograph of the dish in this image."))));
            usage = response.usage();
            if (response.refusalCategory() != null) {
                log.info("Capture {}: second look at the dish photo refused ({}), the first box stays", capture.id(),
                        response.refusalCategory());
            } else {
                Optional<RecipePrompt.Box> refined = RecipePrompt.parseDishPhotoBox(response.text());
                if (refined.isEmpty()) {
                    log.info("Capture {}: no dish photo on the second look", capture.id());
                    return new Result(DishPhotoFinding.NONE, usage);
                }
                Region second = within(search, refined.get(), original);
                if (plausibleRefinement(photo, second)) {
                    photo = second;
                } else {
                    log.info("Capture {}: second look at the dish photo gave {}, far from the first box {}; the first box stays",
                            capture.id(), second, photo);
                }
            }
        } catch (LlmException | IllegalArgumentException e) {
            log.info("Capture {}: second look at the dish photo failed, the first box stays: {}", capture.id(), e.getMessage());
        }

        if (Math.min(photo.width(), photo.height()) < Images.PHOTO_MIN_EDGE) {
            log.info("Capture {}: dish photo {}x{} in {} is too small, ignored", capture.id(), photo.width(), photo.height(),
                    sent.attachment().name());
            return new Result(DishPhotoFinding.NONE, usage);
        }
        BufferedImage cutOut = Images.rotate(original.getSubimage(photo.x(), photo.y(), photo.width(), photo.height()),
                dishPhoto.rotation());
        BlobRef blob = blobs.put(new ByteArrayInputStream(Images.jpeg(Images.fit(cutOut, Images.PHOTO_MAX_EDGE))));
        return new Result(new DishPhotoFinding(new DishPhotoFinding.Photo(sent.attachment().name(), blob.sha256(),
                blob.sizeBytes(), photo.x(), photo.y(), photo.width(), photo.height(), dishPhoto.rotation())), usage);
    }

    /** The box (thousandths of {@code area}) in the original's pixels, clamped to the original. */
    static Region within(Region area, RecipePrompt.Box box, BufferedImage original) {
        int left = clamp(area.x() + (int) Math.round(area.width() * box.left() / 1000.0), original.getWidth());
        int right = clamp(area.x() + (int) Math.round(area.width() * box.right() / 1000.0), original.getWidth());
        int top = clamp(area.y() + (int) Math.round(area.height() * box.top() / 1000.0), original.getHeight());
        int bottom = clamp(area.y() + (int) Math.round(area.height() * box.bottom() / 1000.0), original.getHeight());
        return new Region(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    /**
     * The second look corrects the first box, it does not move it elsewhere: it keeps at least a third of the first
     * box's area and at least a third of it lies on the first box (seen on Haiku: once a 1034 × 34 px strip for a
     * 1054 × 748 px photo; a cut-off photo widened 2.7 times is fine).
     */
    static boolean plausibleRefinement(Region first, Region second) {
        long firstArea = (long) first.width() * first.height();
        long secondArea = (long) second.width() * second.height();
        int overlapWidth = Math.min(first.x() + first.width(), second.x() + second.width()) - Math.max(first.x(), second.x());
        int overlapHeight = Math.min(first.y() + first.height(), second.y() + second.height()) - Math.max(first.y(), second.y());
        long overlap = overlapWidth <= 0 || overlapHeight <= 0 ? 0 : (long) overlapWidth * overlapHeight;
        return secondArea * 3 >= firstArea && overlap * 3 >= secondArea;
    }

    static Region expand(Region box, BufferedImage original) {
        int marginX = (int) Math.round(box.width() * SEARCH_MARGIN);
        int marginY = (int) Math.round(box.height() * SEARCH_MARGIN);
        int left = Math.max(0, box.x() - marginX);
        int top = Math.max(0, box.y() - marginY);
        int right = Math.min(original.getWidth(), box.x() + box.width() + marginX);
        int bottom = Math.min(original.getHeight(), box.y() + box.height() + marginY);
        return new Region(left, top, right - left, bottom - top);
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }
}
