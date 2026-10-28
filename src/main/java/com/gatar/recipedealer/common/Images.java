package com.gatar.recipedealer.common;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Image re-encoding shared by the LLM input and the PDF photos. */
public final class Images {

    /** A dish photo (from a PDF or cut out of a photo) has at least this shorter edge; smaller ones are not photos. */
    public static final int PHOTO_MIN_EDGE = 200;
    /** Dish photos are stored as JPEG of at most this longer edge. */
    public static final int PHOTO_MAX_EDGE = 1600;

    private Images() {
    }

    /** RGB copy (transparency on white) scaled by {@code factor}. */
    public static BufferedImage scale(BufferedImage source, double factor) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** RGB copy whose longer edge is at most {@code maxEdge} (never enlarged). */
    public static BufferedImage fit(BufferedImage source, int maxEdge) {
        return scale(source, Math.min(1.0, (double) maxEdge / Math.max(source.getWidth(), source.getHeight())));
    }

    /** RGB copy turned clockwise by {@code degrees} (a multiple of 90); the source itself when it is 0. */
    public static BufferedImage rotate(BufferedImage source, int degrees) {
        int turns = Math.floorMod(degrees / 90, 4);
        if (turns == 0) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = turns % 2 == 1;
        BufferedImage target = new BufferedImage(swap ? height : width, swap ? width : height, BufferedImage.TYPE_INT_RGB);
        AffineTransform transform = new AffineTransform();
        switch (turns) {
            case 1 -> transform.translate(height, 0);
            case 2 -> transform.translate(width, height);
            default -> transform.translate(0, width);
        }
        transform.quadrantRotate(turns);
        Graphics2D g = target.createGraphics();
        try {
            g.drawImage(source, transform, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    public static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
