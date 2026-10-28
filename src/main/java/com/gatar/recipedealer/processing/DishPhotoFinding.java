package com.gatar.recipedealer.processing;

/**
 * What an LLM step found about the dish photo among the capture's photos and screenshots.
 *
 * @param photo the photo cut out of an attachment, null when none of them shows the dish
 */
public record DishPhotoFinding(Photo photo) {

    public static final DishPhotoFinding NONE = new DishPhotoFinding(null);

    /** The cut-out (a JPEG blob) and its box in the original attachment's pixels. */
    public record Photo(String attachmentName, String blobSha256, long sizeBytes, int x, int y, int width, int height) {
    }
}
