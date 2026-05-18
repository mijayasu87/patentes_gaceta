package ec.gob.senadi.pdftool.pdf;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

public class FormularioProcessor {

    private static final float Y_TOLERANCE = 2.0f;

    public void ocultarTelefonoYFax(File inputFile, File outputFile) throws IOException {
        try (PDDocument doc = PDDocument.load(inputFile)) {
            BuscadorContacto buscador = new BuscadorContacto();
            buscador.setStartPage(1);
            buscador.setEndPage(doc.getNumberOfPages());
            buscador.getText(doc);

            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                int numPagina = i + 1;
                List<Coordenada> coords = buscador.coordsPorPagina.getOrDefault(numPagina, new ArrayList<>());

                if (!coords.isEmpty()) {
                    eliminarValoresDelStream(doc, page, coords);

                    try (PDPageContentStream stream = new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        for (Coordenada coord : coords) {
                            escribirLineas(stream, coord);
                        }
                    }
                }
            }
            doc.save(outputFile);
        }
    }

    private void eliminarValoresDelStream(PDDocument doc, PDPage page,
                                          List<Coordenada> coords) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();
        List<Object> tokens = parser.getTokens();

        List<float[]> targets = new ArrayList<>();
        for (Coordenada c : coords) {
            targets.add(new float[]{ c.y, c.x + 200, c.x + 460 });
        }

        List<Object> newTokens = new ArrayList<>(tokens.size());
        boolean dentroDeBloque = false;
        float curX = 0, curY = 0;
        boolean eliminar = false;

        for (Object token : tokens) {
            if (token instanceof Operator) {
                Operator op = (Operator) token;
                String nombre = op.getName();

                switch (nombre) {
                    case "BT":
                        dentroDeBloque = true;
                        curX = 0;
                        curY = 0;
                        eliminar = false;
                        newTokens.add(token);
                        break;

                    case "ET":
                        dentroDeBloque = false;
                        eliminar = false;
                        newTokens.add(token);
                        break;

                    case "Tm": {
                        int sz = newTokens.size();
                        if (dentroDeBloque && sz >= 6) {
                            curX = floatVal(newTokens.get(sz - 2));
                            curY = floatVal(newTokens.get(sz - 1));
                            eliminar = estaEnZonaDeValor(curX, curY, targets);
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "Td":
                    case "TD": {
                        int sz = newTokens.size();
                        if (dentroDeBloque && sz >= 2) {
                            curX += floatVal(newTokens.get(sz - 2));
                            curY += floatVal(newTokens.get(sz - 1));
                            eliminar = estaEnZonaDeValor(curX, curY, targets);
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "Tj": {
                        if (eliminar) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSString) {
                                newTokens.set(sz - 1, new COSString(""));
                            }
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "TJ": {
                        if (eliminar) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSArray) {
                                COSArray arr = (COSArray) newTokens.get(sz - 1);
                                COSArray limpio = new COSArray();
                                for (int j = 0; j < arr.size(); j++) {
                                    COSBase item = arr.get(j);
                                    if (item instanceof COSString) {
                                        limpio.add(new COSString(""));
                                    } else {
                                        limpio.add(item);
                                    }
                                }
                                newTokens.set(sz - 1, limpio);
                            }
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "'": {
                        if (eliminar) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSString) {
                                newTokens.set(sz - 1, new COSString(""));
                            }
                        }
                        newTokens.add(token);
                        break;
                    }

                    default:
                        newTokens.add(token);
                        break;
                }
            } else {
                newTokens.add(token);
            }
        }

        PDStream newStream = new PDStream(doc);
        OutputStream out = newStream.createOutputStream(COSName.FLATE_DECODE);
        ContentStreamWriter writer = new ContentStreamWriter(out);
        writer.writeTokens(newTokens);
        out.close();
        page.setContents(newStream);
    }

    private boolean estaEnZonaDeValor(float x, float y, List<float[]> targets) {
        for (float[] t : targets) {
            float targetY = t[0];
            float minX    = t[1];
            float maxX    = t[2];
            if (Math.abs(y - targetY) < Y_TOLERANCE && x >= minX && x <= maxX) {
                return true;
            }
        }
        return false;
    }

    private float floatVal(Object obj) {
        if (obj instanceof COSFloat)   return ((COSFloat) obj).floatValue();
        if (obj instanceof COSInteger) return ((COSInteger) obj).floatValue();
        return 0;
    }

    private void escribirLineas(PDPageContentStream stream, Coordenada coord) throws IOException {
        stream.setNonStrokingColor(Color.BLACK);
        stream.beginText();
        stream.setFont(PDType1Font.HELVETICA, 10);
        stream.newLineAtOffset(coord.x + 260, coord.y);
        stream.showText("---");
        stream.endText();
    }

    private static class Coordenada {
        float x, y;
        Coordenada(float x, float y) { this.x = x; this.y = y; }
    }

    private static class BuscadorContacto extends PDFTextStripper {
        Map<Integer, List<Coordenada>> coordsPorPagina = new HashMap<>();

        BuscadorContacto() throws IOException { super(); }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            String textoLimpio = text.trim().toLowerCase();
            textoLimpio = textoLimpio.replace("é", "e");

            if (textoLimpio.equals("telefono") || textoLimpio.equals("teléfono") || textoLimpio.equals("fax")) {
                TextPosition pos = textPositions.get(0);
                int numPagina = getCurrentPageNo();
                coordsPorPagina.putIfAbsent(numPagina, new ArrayList<>());
                coordsPorPagina.get(numPagina).add(
                        new Coordenada(pos.getXDirAdj(), pos.getTextMatrix().getTranslateY()));
            }
            super.writeString(text, textPositions);
        }
    }
}
