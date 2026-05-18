package ec.gob.senadi.pdftool.pdf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import ec.gob.senadi.pdftool.model.PatentData;

/**
 * Genera el PDF con los datos de la patente usando OpenPDF.
 * Layout de alto nivel: paginación automática, wrapping, márgenes protegidos.
 */
public class PatentPdfGenerator {

    private static final float MARGIN_LEFT   = 55;
    private static final float MARGIN_RIGHT  = 55;
    private static final float MARGIN_TOP    = 50;
    private static final float MARGIN_BOTTOM = 60;

    private static final Font FONT_BOLD   = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font FONT_NORMAL = new Font(Font.HELVETICA, 10, Font.NORMAL);

    public static float getMarginLeft() { return MARGIN_LEFT; }
    public static float getMarginRight() { return MARGIN_RIGHT; }
    public static float getMarginTop() { return MARGIN_TOP; }
    public static float getMarginBottom() { return MARGIN_BOTTOM; }

    /** Resultado: documento + writer aún abiertos para seguir escribiendo. */
    public static class GenerateResult {
        public final Document document;
        public final PdfWriter writer;
        public final File outputFile;

        public GenerateResult(Document document, PdfWriter writer, File outputFile) {
            this.document = document;
            this.writer = writer;
            this.outputFile = outputFile;
        }

        /** Espacio vertical restante en la página actual (en puntos). */
        public float getRemainingY() {
            return writer.getVerticalPosition(false) - MARGIN_BOTTOM;
        }
    }

    /** Genera y guarda a archivo (cierra el documento). */
    public void generate(PatentData data, File output) throws IOException, DocumentException {
        GenerateResult result = generateDocument(data, null, output);
        result.document.close();
    }

    /**
     * Crea un documento A4 vacío con la configuración estándar SENADI.
     * Se usa para composición por lote en un único flujo continuo.
     */
    public GenerateResult createEmptyDocument(File outputFile)
            throws IOException, DocumentException {
        Document doc = new Document(PageSize.A4, MARGIN_LEFT, MARGIN_RIGHT, MARGIN_TOP, MARGIN_BOTTOM);
        PdfWriter writer = PdfWriter.getInstance(doc, new FileOutputStream(outputFile));
        doc.open();
        return new GenerateResult(doc, writer, outputFile);
    }

    /**
     * Genera el documento con los datos de la patente SIN cerrarlo.
     * Si separatorImage no es null, dibuja la imagen al inicio como separador.
     * Devuelve el Document y PdfWriter abiertos para que el caller pueda
     * seguir añadiendo contenido (reivindicaciones, etc.) en la misma página.
     */
    public GenerateResult generateDocument(PatentData data, byte[] separatorImageBytes, File outputFile)
            throws IOException, DocumentException {
        GenerateResult result = createEmptyDocument(outputFile);
        Document doc = result.document;

        // ── Separador (imagen centrada) al inicio ──
        if (separatorImageBytes != null && separatorImageBytes.length > 0) {
            Image img = Image.getInstance(separatorImageBytes);
            float maxH = 14f;
            float usableWidth = PageSize.A4.getWidth() - MARGIN_LEFT - MARGIN_RIGHT;
            float scale = maxH / img.getHeight();
            float scaledWidth = Math.min(img.getWidth() * scale * 1.3f, usableWidth);
            img.scaleAbsolute(scaledWidth, maxH);
            img.setAlignment(Element.ALIGN_CENTER);
            doc.add(img);
            doc.add(new Paragraph(" ", new Font(Font.HELVETICA, 4)));
        }

            appendPatentDataBlock(doc, data);

            return result;
            }

            /**
             * Inserta el bloque de datos de una patente dentro de un documento ya abierto.
             * Mantiene exactamente el layout actual del flujo individual.
             */
            public void appendPatentDataBlock(Document doc, PatentData data)
                throws DocumentException {
            // ── Campos de datos ──
        addInlineField(doc, "Tipo de patente: ",
                defaultStr(data.getTipoPatente(), "Patente"));
        addInlineField(doc, "No. De Solicitud: ",
                defaultStr(data.getApplicationNumber(), "\u2014"));
        addInlineField(doc, "Fecha de solicitud: ",
                defaultStr(data.getFechaSolicitudTexto(), "\u2014"));
        addInlineField(doc, "Título de la Patente: ",
                defaultStr(data.getTitulo(), "\u2014"));
        addInlineField(doc, "Clasificación internacional de patente: ",
                defaultStr(data.getClasificacionInternacional(), ""));
        addInlineField(doc, "Solicitante: ",
                defaultStr(data.getSolicitantesTexto(), "\u2014"));
        addInlineField(doc, "País: ",
                defaultStr(data.getPaisesTexto(), "\u2014"));
        addInlineField(doc, "Fecha de Prioridad: ",
                defaultStr(data.getFechaPrioridadTexto(), ""));
        addInlineField(doc, "Representante/Apoderado: ",
                defaultStr(data.getRepresentanteTexto(), ""));

        // ── Resumen (justificado) ──
        String resumen = data.getResumen();
        if (resumen != null && !resumen.trim().isEmpty()) {
            addJustifiedField(doc, "Resumen: ", resumen.trim());
        }
    }

    // ── Dibujo de campos ─────────────────────────────────────────

    /** Campo inline: etiqueta bold + valor normal, flujo automático. */
    private void addInlineField(Document doc, String label, String value)
            throws DocumentException {
        if (value == null) value = "";
        Paragraph p = new Paragraph();
        p.setLeading(13f);
        p.add(new Chunk(label, FONT_BOLD));
        p.add(new Chunk(sanitize(value), FONT_NORMAL));
        doc.add(p);
    }

    /** Campo con texto justificado: etiqueta bold + valor normal. */
    private void addJustifiedField(Document doc, String label, String value)
            throws DocumentException {
        if (value == null) value = "";
        Paragraph p = new Paragraph();
        p.setLeading(13f);
        p.setAlignment(Element.ALIGN_JUSTIFIED);
        p.add(new Chunk(label, FONT_BOLD));
        p.add(new Chunk(sanitize(value), FONT_NORMAL));
        doc.add(p);
    }

    // ── Utilidades ───────────────────────────────────────────────

    private String sanitize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c <= 0xFF) sb.append(c);
            else if (c == '\t') sb.append("    ");
            else sb.append(' ');
        }
        return sb.toString();
    }

    private String defaultStr(String val, String def) {
        return (val != null && !val.trim().isEmpty()) ? val.trim() : def;
    }
}
