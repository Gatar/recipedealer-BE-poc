package com.gatar.recipedealer.pdfimage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.gatar.recipedealer.common.Images;

/**
 * Finds the photo of a PDF: the largest image of the first page that has one, pages in order. Images whose shorter
 * edge is below {@link #MIN_EDGE} (logos, icons, rules) and stencil masks do not count; an image that cannot be decoded
 * is skipped. The photo is re-encoded as an RGB JPEG of at most {@code maxEdge} px.
 */
public class PdfImageExtractor {

    static final int MIN_EDGE = Images.PHOTO_MIN_EDGE;
    /** Larger images are skipped instead of decoded (a 50 MP RGB image already takes 200 MB of heap). */
    static final long MAX_PIXELS = 50_000_000L;

    public record Photo(int page, byte[] jpeg, int width, int height) {
    }

    private final int maxEdge;

    public PdfImageExtractor(int maxEdge) {
        this.maxEdge = maxEdge;
    }

    /** @throws IOException if the PDF cannot be read (damaged, password-protected) */
    public Optional<Photo> extract(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                List<PDImageXObject> candidates = new ArrayList<>();
                collect(document.getPage(index).getResources(), candidates, Collections.newSetFromMap(new IdentityHashMap<>()));
                candidates.sort(Comparator.comparingLong((PDImageXObject image) -> (long) image.getWidth() * image.getHeight()).reversed());
                for (PDImageXObject candidate : candidates) {
                    BufferedImage decoded = decode(candidate);
                    if (decoded != null) {
                        BufferedImage photo = Images.fit(decoded, maxEdge);
                        return Optional.of(new Photo(index + 1, Images.jpeg(photo), photo.getWidth(), photo.getHeight()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Images of the resources and of the forms they contain (each form once). */
    private static void collect(PDResources resources, List<PDImageXObject> target, Set<COSBase> seenForms) {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object;
            try {
                object = resources.getXObject(name);
            } catch (IOException e) {
                continue;
            }
            if (object instanceof PDImageXObject image) {
                if (!image.isStencil() && Math.min(image.getWidth(), image.getHeight()) >= MIN_EDGE
                        && (long) image.getWidth() * image.getHeight() <= MAX_PIXELS) {
                    target.add(image);
                }
            } else if (object instanceof PDFormXObject form && seenForms.add(form.getCOSObject())) {
                collect(form.getResources(), target, seenForms);
            }
        }
    }

    private static BufferedImage decode(PDImageXObject image) {
        try {
            return image.getImage();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
