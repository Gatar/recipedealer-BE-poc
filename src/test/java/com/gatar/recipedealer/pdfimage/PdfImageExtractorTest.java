package com.gatar.recipedealer.pdfimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class PdfImageExtractorTest {

    private final PdfImageExtractor extractor = new PdfImageExtractor(1600);

    @Test
    void largestImageOfTheFirstPageWithOneIsThePhoto() throws IOException {
        byte[] pdf = pdf(document -> {
            page(document, page -> { });
            page(document, page -> {
                draw(document, page, image(400, 300, Color.BLUE));
                draw(document, page, image(800, 600, Color.RED));
            });
            page(document, page -> draw(document, page, image(1200, 900, Color.GREEN)));
        });

        var photo = extractor.extract(pdf).orElseThrow();

        assertThat(photo.page()).isEqualTo(2);
        assertThat(photo.width()).isEqualTo(800);
        assertThat(photo.height()).isEqualTo(600);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(photo.jpeg()));
        assertThat(decoded.getWidth()).isEqualTo(800);
        assertThat(new Color(decoded.getRGB(400, 300)).getRed()).isGreaterThan(200);
    }

    @Test
    void smallImagesDoNotCount() throws IOException {
        byte[] pdf = pdf(document -> {
            page(document, page -> draw(document, page, image(300, 80, Color.BLACK)));
            page(document, page -> draw(document, page, image(500, 400, Color.RED)));
        });

        assertThat(extractor.extract(pdf).orElseThrow().page()).isEqualTo(2);
    }

    @Test
    void imageInsideAFormCounts() throws IOException {
        byte[] pdf = pdf(document -> page(document, page -> {
            try {
                PDFormXObject form = new PDFormXObject(document);
                form.setBBox(PDRectangle.A4);
                PDResources resources = new PDResources();
                resources.add(LosslessFactory.createFromImage(document, image(600, 400, Color.RED)));
                form.setResources(resources);
                page.getResources().add(form);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        assertThat(extractor.extract(pdf).orElseThrow().width()).isEqualTo(600);
    }

    @Test
    void largePhotoIsDownscaled() throws IOException {
        byte[] pdf = pdf(document -> page(document, page -> draw(document, page, image(3200, 2400, Color.RED))));

        var photo = extractor.extract(pdf).orElseThrow();

        assertThat(photo.width()).isEqualTo(1600);
        assertThat(photo.height()).isEqualTo(1200);
    }

    @Test
    void textOnlyPdfHasNoPhoto() throws IOException {
        byte[] pdf = pdf(document -> page(document, page -> {
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Sernik: 1 kg twarogu");
                content.endText();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        assertThat(extractor.extract(pdf)).isEmpty();
    }

    @Test
    void damagedPdfFails() {
        assertThatThrownBy(() -> extractor.extract("not a pdf".getBytes())).isInstanceOf(IOException.class);
    }

    private static byte[] pdf(Consumer<PDDocument> pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            pages.accept(document);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void page(PDDocument document, Consumer<PDPage> content) {
        PDPage page = new PDPage(PDRectangle.A4);
        page.setResources(new PDResources());
        document.addPage(page);
        content.accept(page);
    }

    private static void draw(PDDocument document, PDPage page, BufferedImage image) {
        try (var content = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, false)) {
            PDImageXObject object = LosslessFactory.createFromImage(document, image);
            content.drawImage(object, 50, 50, 200, 150);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static BufferedImage image(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }
}
