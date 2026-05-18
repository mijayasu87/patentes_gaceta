package ec.gob.senadi.pdftool.pdf;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

import ec.gob.senadi.pdftool.model.PatentData;

/**
 * Renderiza un separador visual entre patentes en el flujo por lote.
 */
public class PatentSectionSeparatorRenderer {

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font META_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL);
    private static final float BLOCK_HEIGHT = 42f;

    public float getRequiredHeight() {
        return BLOCK_HEIGHT;
    }

    public void appendSeparator(Document doc, PdfWriter writer, PatentData nextPatent)
            throws DocumentException {
        ensureSpace(doc, writer, BLOCK_HEIGHT);

        PdfContentByte cb = writer.getDirectContent();
        float left = doc.left();
        float right = doc.right();
        float y = writer.getVerticalPosition(false) - 6f;

        cb.saveState();
        cb.setLineWidth(1.2f);
        cb.setRGBColorStroke(26, 71, 125);
        cb.moveTo(left, y);
        cb.lineTo(right, y);
        cb.stroke();
        cb.restoreState();

        Paragraph title = new Paragraph();
        title.setSpacingBefore(8f);
        title.setSpacingAfter(2f);
        title.add(new Chunk("Siguiente patente", TITLE_FONT));
        doc.add(title);

        Paragraph meta = new Paragraph();
        meta.setSpacingAfter(10f);
        meta.add(new Chunk(nextPatent.getApplicationNumber() + "  |  ", META_FONT));
        meta.add(new Chunk(safe(nextPatent.getTipoPatente()), META_FONT));
        if (nextPatent.getTitulo() != null && !nextPatent.getTitulo().trim().isEmpty()) {
            meta.add(new Chunk("  |  " + truncate(nextPatent.getTitulo(), 85), META_FONT));
        }
        doc.add(meta);
    }

    private void ensureSpace(Document doc, PdfWriter writer, float requiredHeight) {
        float available = writer.getVerticalPosition(false) - PatentPdfGenerator.getMarginBottom();
        if (available < requiredHeight) {
            doc.newPage();
        }
    }

    private String safe(String value) {
        return value != null ? value : "Patente";
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen - 3) + "..." : trimmed;
    }
}
