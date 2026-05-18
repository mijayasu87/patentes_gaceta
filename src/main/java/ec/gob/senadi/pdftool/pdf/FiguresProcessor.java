package ec.gob.senadi.pdftool.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

public class FiguresProcessor {

    private static final float MARGIN = 50f;
    private static final Font LABEL_FONT = new Font(Font.HELVETICA, 12, Font.BOLD);

    private static final Pattern FIGURE_LABEL_RE =
            Pattern.compile("(?i)(figura|fig\\.?|figure|dibujo|l[aá]mina|plano)\\s*\\d+");

    public void processFigures(File inputPdf, File outputPdf) throws Exception {
        List<ImageData> images = extractImages(inputPdf);

        if (images.isEmpty()) {
            throw new IllegalStateException("No se encontraron imagenes en el PDF de figuras.");
        }

        Document doc = new Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN);
        PdfWriter.getInstance(doc, new FileOutputStream(outputPdf));
        doc.open();

        float usableW = PageSize.A4.getWidth() - 2 * MARGIN;
        int figNum = 1;

        for (ImageData imgData : images) {
            // Etiqueta
            String label = imgData.hasLabel ? imgData.originalLabel : ("Figura " + figNum);
            Paragraph labelPara = new Paragraph(label, LABEL_FONT);
            labelPara.setAlignment(Element.ALIGN_CENTER);
            labelPara.setSpacingAfter(5f);
            doc.add(labelPara);

            // Imagen
            Image pdfImage = Image.getInstance(imgData.data);
            pdfImage.setAlignment(Element.ALIGN_CENTER);
            // Escalar para que quepa en el ancho disponible
            if (pdfImage.getWidth() > usableW) {
                pdfImage.scaleToFit(usableW, PageSize.A4.getHeight() - 2 * MARGIN - 40);
            }
            doc.add(pdfImage);

            // Espacio entre figuras
            doc.add(new Paragraph(" ", new Font(Font.HELVETICA, 10)));

            figNum++;
        }

        doc.close();
    }

    private List<ImageData> extractImages(File pdfFile) throws IOException {
        List<ImageData> images = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            int totalPages = doc.getNumberOfPages();

            for (int p = 0; p < totalPages; p++) {
                PDPage page = doc.getPage(p);
                String pageText = extractPageText(doc, p);
                boolean pageHasLabel = FIGURE_LABEL_RE.matcher(pageText).find();

                for (COSName name : page.getResources().getXObjectNames()) {
                    PDXObject xobj = page.getResources().getXObject(name);
                    if (xobj instanceof PDImageXObject) {
                        PDImageXObject img = (PDImageXObject) xobj;
                        byte[] data = toBytes(img.getImage(), img.getSuffix());
                        if (data.length > 1024) {
                            ImageData id = new ImageData();
                            id.data = data;
                            id.hasLabel = pageHasLabel;
                            id.originalLabel = pageHasLabel ? extractLabel(pageText) : null;
                            images.add(id);
                        }
                    }
                }
            }
        }
        return images;
    }

    private String extractPageText(PDDocument doc, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(doc);
    }

    private String extractLabel(String pageText) {
        java.util.regex.Matcher m = FIGURE_LABEL_RE.matcher(pageText);
        return m.find() ? m.group() : null;
    }

    private byte[] toBytes(BufferedImage image, String suffix) throws IOException {
        String format = (suffix != null && !suffix.isEmpty()) ? suffix : "png";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, baos);
            return baos.toByteArray();
        }
    }

    private static class ImageData {
        byte[] data;
        boolean hasLabel;
        String originalLabel;
    }
}
