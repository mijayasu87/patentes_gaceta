package ec.gob.senadi.pdftool.pdf;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;

public class PdfBackgroundCleaner {

    /**
     * Porcentaje del área de la página: solo eliminar fills de rectángulos
     * cuya ÁREA supere este porcentaje. Rectángulos más chicos (parches
     * de corrección, subrayados, etc.) se preservan intactos.
     */
    private static final float MIN_AREA_RATIO = 0.25f;  // 25% del área de la página

    /**
     * Elimina SOLO los rellenos de rectángulos que cubren una porción significativa
     * de la página (fondos completos). Preserva todo lo demás: subrayados, bordes,
     * parches de corrección, líneas.
     */
    public static void removeBackgroundRectangles(File pdfFile) throws Exception {
        byte[] data = java.nio.file.Files.readAllBytes(pdfFile.toPath());
        try (PDDocument doc = PDDocument.load(data)) {
            boolean modified = false;

            for (PDPage page : doc.getPages()) {
                PDRectangle mb = page.getMediaBox();
                float pageArea = mb.getWidth() * mb.getHeight();

                PDFStreamParser parser = new PDFStreamParser(page);
                parser.parse();
                List<Object> tokens = parser.getTokens();
                List<Object> newTokens = new ArrayList<>();
                boolean pageModified = false;

                // Rastrear el área máxima de rectángulos en el path actual
                float maxRectArea = 0f;
                boolean hasRect = false;

                for (Object token : tokens) {
                    if (token instanceof Operator) {
                        Operator op = (Operator) token;
                        String name = op.getName();

                        // ── Construcción de path: rastrear área de rectángulos ──
                        if ("re".equals(name)) {
                            int sz = newTokens.size();
                            if (sz >= 4) {
                                float w = Math.abs(toFloat(newTokens.get(sz - 2)));
                                float h = Math.abs(toFloat(newTokens.get(sz - 1)));
                                float area = w * h;
                                if (area > maxRectArea) maxRectArea = area;
                            }
                            hasRect = true;
                            newTokens.add(token);
                            continue;
                        }

                        if ("m".equals(name) || "l".equals(name) || "c".equals(name)
                                || "v".equals(name) || "y".equals(name) || "h".equals(name)) {
                            newTokens.add(token);
                            continue;
                        }

                        boolean isLargeBackground = hasRect
                                && pageArea > 0
                                && (maxRectArea / pageArea) >= MIN_AREA_RATIO;

                        // ── Fill: solo suprimir si es fondo grande ──
                        if ("f".equals(name) || "F".equals(name) || "f*".equals(name)) {
                            if (isLargeBackground) {
                                newTokens.add(Operator.getOperator("n"));
                                pageModified = true;
                            } else {
                                newTokens.add(token); // preservar
                            }
                            hasRect = false;
                            maxRectArea = 0f;
                            continue;
                        }

                        // ── Close+fill+stroke ──
                        if ("b".equals(name) || "b*".equals(name)) {
                            if (isLargeBackground) {
                                newTokens.add(Operator.getOperator("s"));
                                pageModified = true;
                            } else {
                                newTokens.add(token);
                            }
                            hasRect = false;
                            maxRectArea = 0f;
                            continue;
                        }
                        if ("B".equals(name) || "B*".equals(name)) {
                            if (isLargeBackground) {
                                newTokens.add(Operator.getOperator("S"));
                                pageModified = true;
                            } else {
                                newTokens.add(token);
                            }
                            hasRect = false;
                            maxRectArea = 0f;
                            continue;
                        }

                        // ── Otros operadores que consumen/terminan el path ──
                        if ("S".equals(name) || "s".equals(name) || "n".equals(name)
                                || "W".equals(name) || "W*".equals(name)) {
                            hasRect = false;
                            maxRectArea = 0f;
                        }
                    }
                    newTokens.add(token);
                }

                if (pageModified) {
                    PDStream newContents = new PDStream(doc);
                    try (OutputStream out = newContents.createOutputStream(COSName.FLATE_DECODE)) {
                        ContentStreamWriter writer = new ContentStreamWriter(out);
                        writer.writeTokens(newTokens);
                    }
                    page.setContents(newContents);
                    modified = true;
                }
            }

            if (modified) {
                doc.save(pdfFile);
            }
        }
    }

    private static float toFloat(Object o) {
        return (o instanceof COSNumber) ? ((COSNumber) o).floatValue() : 0f;
    }
}
