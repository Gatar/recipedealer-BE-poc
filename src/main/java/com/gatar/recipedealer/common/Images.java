package com.gatar.recipedealer.common;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Image re-encoding shared by the LLM input and the stored photos. */
public final class Images {

    /** A dish photo (from a PDF or cut out of a photo) has at least this shorter edge; smaller ones are not photos. */
    public static final int PHOTO_MIN_EDGE = 200;
    /**
     * Stored photos (uploaded, downloaded, from PDFs, dish cut-outs) are WebP of at most this longer edge (user,
     * 2026-11-27: storage savings; enough for a tablet's full width).
     */
    public static final int PHOTO_MAX_EDGE = 1280;
    public static final String WEBP = "image/webp";
    /** cwebp quality (0–100) of the stored photos. */
    static final int WEBP_QUALITY = 80;
    private static final Duration CWEBP_TIMEOUT = Duration.ofSeconds(60);

    private Images() {
    }

    /**
     * A stored photo: at most {@link #PHOTO_MAX_EDGE} px on the longer edge, as lossy WebP without metadata. Encoded by
     * {@code cwebp} (package {@code webp}; Java has no WebP encoder), fed our own decoded pixels as PPM.
     */
    public static byte[] photo(BufferedImage image) throws IOException {
        return webp(fit(image, PHOTO_MAX_EDGE));
    }

    public static byte[] webp(BufferedImage image) throws IOException {
        BufferedImage rgb = image.getType() == BufferedImage.TYPE_INT_RGB ? image : scale(image, 1.0);
        int width = rgb.getWidth();
        int height = rgb.getHeight();
        byte[] header = ("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII);
        int[] pixels = rgb.getRGB(0, 0, width, height, null, 0, width);
        byte[] ppm = new byte[header.length + pixels.length * 3];
        System.arraycopy(header, 0, ppm, 0, header.length);
        for (int i = 0, o = header.length; i < pixels.length; i++) {
            ppm[o++] = (byte) (pixels[i] >> 16);
            ppm[o++] = (byte) (pixels[i] >> 8);
            ppm[o++] = (byte) pixels[i];
        }
        Process process = new ProcessBuilder("cwebp", "-quiet", "-q", Integer.toString(WEBP_QUALITY), "-metadata", "none",
                "-o", "-", "--", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        // stdin on its own thread: cwebp may start writing before it has read everything
        Thread feeder = Thread.ofVirtual().start(() -> {
            try (OutputStream in = process.getOutputStream()) {
                in.write(ppm);
            } catch (IOException ignored) {
                // cwebp exited early; its exit code tells
            }
        });
        byte[] webp;
        try (InputStream out = process.getInputStream()) {
            webp = out.readAllBytes();
        }
        try {
            if (!process.waitFor(CWEBP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("cwebp did not finish in " + CWEBP_TIMEOUT);
            }
            feeder.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while encoding WebP", e);
        }
        if (process.exitValue() != 0 || !isWebp(webp)) {
            throw new IOException("cwebp failed (exit " + process.exitValue() + ", " + webp.length + " bytes)");
        }
        return webp;
    }

    /** Larger images are decoded subsampled, so a huge one never needs more memory than this many pixels. */
    private static final long MAX_DECODED_PIXELS = 4000L * 4000L;

    /**
     * Decodes an image of any format ImageIO reads (JPEG, PNG, GIF, BMP, WebP), subsampled above
     * {@link #MAX_DECODED_PIXELS}; null if no reader knows the format.
     *
     * @throws IOException if the image is damaged
     */
    public static BufferedImage read(byte[] content) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return null;
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
            } catch (RuntimeException e) {
                throw new IOException(e.getMessage(), e);
            } finally {
                reader.dispose();
            }
        }
    }

    /** RIFF container with the WEBP form type. */
    public static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
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
