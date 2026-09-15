package ec.gob.senadi.pdftool.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Detecta encabezados y pies de página en PDFs de reivindicaciones.
 *
 * Por cada página del rango analizado devuelve un rango Y "limpio"
 * {@code [topPdfY, bottomPdfY]} (en coords PDF, Y desde abajo) que excluye las
 * líneas detectadas como header (banda superior) o footer (banda inferior).
 *
 * <p>Estrategia híbrida:</p>
 * <ul>
 *   <li>En bandas físicas top/bottom de cada página, identifica líneas candidatas.</li>
 *   <li>Si una línea (texto normalizado quitando dígitos) se repite en otra
 *       página → header/footer.</li>
 *   <li>Si matchea un patrón típico (numeración, referencia, fecha, marca) →
 *       header/footer.</li>
 * </ul>
 *
 * <p>Compartido entre el flujo individual ({@code PdfProcessingService.insertClaimContentStream})
 * y el flujo por lote ({@code BatchPatentPdfComposer.appendClaims}) para que ambos
 * limpien el contenido fuente de forma idéntica.</p>
 */
public final class ClaimsHeaderFooterDetector {

    private static final Logger LOG = Logger.getLogger(ClaimsHeaderFooterDetector.class.getName());

    public static final float HF_TOP_BAND_PT    = 130f;
    public static final float HF_BOTTOM_BAND_PT = 80f;

    /** Patrones típicos de header/footer (numeración, refs, fechas, marcas). */
    private static final Pattern[] HF_PATTERNS = {
        Pattern.compile("^\\s*-?\\s*\\d{1,4}\\s*-?\\s*$"),                            // "17", "-3-", "- 450 -"
        Pattern.compile("^\\s*\\d+\\s*[/\\\\]\\s*\\d+\\s*$"),                          // "23/29"
        Pattern.compile("(?i)^\\s*(p[áa]gina|p[áa]g\\.?|page)\\s*\\d+.*$"),            // "Página 1", "Page 5"
        Pattern.compile(
            "(?i)^\\s*(uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|"
          + "trece|catorce|quince|diecis[ée]is|diecisiete|dieciocho|diecinueve|veinte|"
          + "veintiuno|veintid[oó]s|veintitr[ée]s|veinticuatro|veinticinco|veintis[ée]is|"
          + "veintisiete|veintiocho|veintinueve|treinta|cuarenta|cincuenta|sesenta|setenta|"
          + "ochenta|noventa|cien|ciento|doscientos|trescientos|cuatrocientos|quinientos|"
          + "seiscientos|setecientos|ochocientos|novecientos|mil)"
          + "(\\s+(y|de)\\s+[\\wáéíóúñü]+)*\\s*$"),                                    // "Diecisiete", "Cuarenta y cinco"
        Pattern.compile("(?i).*\\b(pct/|your\\s+ref|our\\s+ref|ref\\.?\\s*no\\.?|docket\\s*no\\.?|attorney\\s+docket|file\\s*no\\.?|matter\\s*no\\.?|case\\s*no\\.?|serial\\s*no\\.?|application\\s*no\\.?).*"),
        Pattern.compile("^[A-Z][a-z]+\\s+\\d{1,2},?\\s+\\d{4}\\s*$"),                  // "December 11, 2023"
        Pattern.compile("(?i).*\\b(confidencial|confidential|privileged|proprietary|do\\s+not\\s+copy|all\\s+rights\\s+reserved)\\b.*"),
        Pattern.compile("^[A-Z]{2,}[-/][A-Z0-9./-]{2,}\\s*$"),                         // "QRL-014EC", "IPTS/125381436.1"
    };

    private ClaimsHeaderFooterDetector() { }

    /** Línea de texto con su posición Y aproximada en coordenadas PDF (Y desde abajo). */
    private static final class TextLine {
        final String text;
        final float yPdfBottom;
        final float yPdfTop;
        TextLine(String text, float yPdfBottom, float yPdfTop) {
            this.text = text;
            this.yPdfBottom = yPdfBottom;
            this.yPdfTop = yPdfTop;
        }
    }

    /**
     * Stripper que captura líneas con su posición Y en coordenadas PDF.
     */
    private static final class LineExtractor extends PDFTextStripper {
        final List<TextLine> lines = new ArrayList<>();
        float pageHeight;

        LineExtractor() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.isEmpty() || positions == null || positions.isEmpty()) return;

            float minDirAdj = Float.POSITIVE_INFINITY;
            float maxDirAdj = Float.NEGATIVE_INFINITY;
            for (TextPosition tp : positions) {
                float y = tp.getYDirAdj();
                float h = tp.getHeightDir();
                if (h <= 0) h = tp.getFontSizeInPt();
                if (h <= 0) h = 10f;
                if (y - h < minDirAdj) minDirAdj = y - h;
                if (y > maxDirAdj)     maxDirAdj = y;
            }
            float yPdfBottom = pageHeight - maxDirAdj;
            float yPdfTop    = pageHeight - minDirAdj;
            lines.add(new TextLine(trimmed, yPdfBottom, yPdfTop));
        }
    }

    /**
     * @return map pageIndex → {topAllowedPdfY, bottomAllowedPdfY}.
     *         Si no se detecta header/footer en una página, top = pageH, bottom = 0.
     */
    public static Map<Integer, float[]> detectBands(
            PDDocument doc, int firstPage, int lastPage) throws IOException {

        Map<Integer, float[]> result = new HashMap<>();
        Map<Integer, List<TextLine>> headerCandsByPage = new HashMap<>();
        Map<Integer, List<TextLine>> footerCandsByPage = new HashMap<>();
        Map<Integer, Float> pageHeights = new HashMap<>();

        for (int p = firstPage; p <= lastPage; p++) {
            if (p < 0 || p >= doc.getNumberOfPages()) continue;
            PDPage page = doc.getPage(p);
            float pageH = page.getMediaBox().getHeight();
            pageHeights.put(p, pageH);

            LineExtractor extractor = new LineExtractor();
            extractor.pageHeight = pageH;
            extractor.setStartPage(p + 1);
            extractor.setEndPage(p + 1);
            extractor.getText(doc);

            List<TextLine> hcands = new ArrayList<>();
            List<TextLine> fcands = new ArrayList<>();
            float headerLimit = pageH - HF_TOP_BAND_PT;
            float footerLimit = HF_BOTTOM_BAND_PT;
            for (TextLine ln : extractor.lines) {
                if (ln.yPdfBottom >= headerLimit) hcands.add(ln);
                else if (ln.yPdfTop <= footerLimit) fcands.add(ln);
            }
            headerCandsByPage.put(p, hcands);
            footerCandsByPage.put(p, fcands);
        }

        boolean multiPage = pageHeights.size() >= 2;
        Set<String> repeatedNorm = new HashSet<>();
        if (multiPage) {
            Map<String, Integer> counts = new HashMap<>();
            for (int p : pageHeights.keySet()) {
                Set<String> seenThisPage = new HashSet<>();
                for (TextLine ln : headerCandsByPage.get(p)) {
                    String n = normalize(ln.text);
                    if (!n.isEmpty() && seenThisPage.add(n)) counts.merge(n, 1, Integer::sum);
                }
                for (TextLine ln : footerCandsByPage.get(p)) {
                    String n = normalize(ln.text);
                    if (!n.isEmpty() && seenThisPage.add(n)) counts.merge(n, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() >= 2) repeatedNorm.add(e.getKey());
            }
        }

        for (int p : pageHeights.keySet()) {
            float pageH = pageHeights.get(p);
            float topAllowed    = pageH;
            float bottomAllowed = 0f;

            for (TextLine ln : headerCandsByPage.get(p)) {
                if (isHF(ln, repeatedNorm)) {
                    float cut = ln.yPdfBottom - 2f;
                    if (cut < topAllowed) topAllowed = cut;
                }
            }
            for (TextLine ln : footerCandsByPage.get(p)) {
                if (isHF(ln, repeatedNorm)) {
                    float cut = ln.yPdfTop + 2f;
                    if (cut > bottomAllowed) bottomAllowed = cut;
                }
            }
            result.put(p, new float[]{topAllowed, bottomAllowed});
            LOG.info("ClaimsHeaderFooterDetector: pág " + (p + 1)
                   + " top=" + topAllowed + " bottom=" + bottomAllowed
                   + " (pageH=" + pageH + ")");
        }
        return result;
    }

    /** Normaliza una línea para comparación inter-página: quita dígitos y espacios. */
    private static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                   .replaceAll("\\d+", "")
                   .replaceAll("\\s+", "")
                   .trim();
    }

    private static boolean isHF(TextLine ln, Set<String> repeatedNorm) {
        String norm = normalize(ln.text);
        if (!norm.isEmpty() && repeatedNorm.contains(norm)) return true;
        for (Pattern p : HF_PATTERNS) {
            if (p.matcher(ln.text).matches()) return true;
        }
        return false;
    }

    /**
     * Detecta sub-rangos Y "densos" (con contenido cercano) dentro de
     * {@code [yBottom, yTop]} de una página, separados por gaps verticales
     * mayores a {@code maxGap}. Sirve para evitar que un gran espacio en blanco
     * del PDF fuente (típicamente por salto de página en el original o por
     * layout con secciones espaciadas) se traslade como hueco al PDF final.
     *
     * <p>Considera tanto líneas de texto como XObjects (imágenes/fórmulas) como
     * contenido — esto evita que una fórmula intercalada entre dos párrafos
     * se interprete como gap y los párrafos terminen montándose en el destino.</p>
     *
     * <p>Los sub-rangos vuelven ordenados de top a bottom (Y descendente).</p>
     *
     * @param maxGap separación máxima permitida (pt) entre items consecutivos;
     *               gaps mayores rompen el sub-rango.
     */
    public static List<float[]> detectDenseSubRanges(
            PDDocument doc, int pageIndex, float yBottom, float yTop, float maxGap)
            throws IOException {

        if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
            return new ArrayList<>();
        }
        PDPage page = doc.getPage(pageIndex);
        float pageH = page.getMediaBox().getHeight();

        // ── Recolectar items de contenido (texto + imágenes/XObjects) ──
        LineExtractor extractor = new LineExtractor();
        extractor.pageHeight = pageH;
        extractor.setStartPage(pageIndex + 1);
        extractor.setEndPage(pageIndex + 1);
        extractor.getText(doc);

        // items: [yTop, yBottom] de cada bloque (texto o imagen)
        List<float[]> items = new ArrayList<>();
        for (TextLine ln : extractor.lines) {
            if (ln.yPdfTop < yBottom || ln.yPdfBottom > yTop) continue;
            items.add(new float[]{ln.yPdfTop, ln.yPdfBottom});
        }
        // XObjects (imágenes, fórmulas) — usar el mismo rastreador de
        // ContentStreamFragmenter para consistencia con el resto del flujo.
        for (float[] img : ContentStreamFragmenter.extractXObjectYRanges(page, yBottom, yTop)) {
            // extractXObjectYRanges devuelve [yBottom, yTop]; normalizar a [yTop, yBottom]
            items.add(new float[]{img[1], img[0]});
        }

        if (items.isEmpty()) return new ArrayList<>();

        // Ordenar top → bottom (Y descendente del top de cada item)
        items.sort((a, b) -> Float.compare(b[0], a[0]));

        List<float[]> ranges = new ArrayList<>();
        float currentTop    = Math.min(yTop, items.get(0)[0] + 4f);
        float currentBottom = items.get(0)[1];

        for (int i = 1; i < items.size(); i++) {
            float[] it = items.get(i);
            float gap = currentBottom - it[0];
            if (gap > maxGap) {
                ranges.add(new float[]{currentTop, Math.max(yBottom, currentBottom - 2f)});
                currentTop = Math.min(yTop, it[0] + 4f);
            }
            currentBottom = Math.min(currentBottom, it[1]);
        }
        ranges.add(new float[]{currentTop, Math.max(yBottom, currentBottom - 2f)});
        return ranges;
    }
}
