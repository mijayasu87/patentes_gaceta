package ec.gob.senadi.pdftool.pdf;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.lowagie.text.DocumentException;

import ec.gob.senadi.pdftool.model.PatentData;

/**
 * Compositor por lote para bloques continuos de patentes.
 *
 * Fase 4: el PDF final lo controla PDFBox para poder insertar
 * datos y reivindicaciones en continuidad real dentro del mismo flujo.
 */
public class BatchPatentPdfComposer {

    private static final Logger LOG = Logger.getLogger(BatchPatentPdfComposer.class.getName());
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float BODY_TOP = PAGE_HEIGHT - PatentPdfGenerator.getMarginTop();
    private static final float BODY_BOTTOM = PatentPdfGenerator.getMarginBottom();
    private static final float MIN_AVAILABLE_HEIGHT = 30f;
    private static final float SEPARATOR_HEIGHT = 42f;

    private final PatentPdfGenerator patentPdfGenerator;
    private final ClaimsFirstLiteralExtractor claimsExtractor;
    private byte[] separatorImageBytes;

    public BatchPatentPdfComposer() {
        this(new PatentPdfGenerator(), new ClaimsFirstLiteralExtractor());
    }

    BatchPatentPdfComposer(PatentPdfGenerator patentPdfGenerator,
                           ClaimsFirstLiteralExtractor claimsExtractor) {
        this.patentPdfGenerator = patentPdfGenerator;
        this.claimsExtractor = claimsExtractor;
    }

    public void setSeparatorImageBytes(byte[] imageBytes) {
        this.separatorImageBytes = imageBytes;
    }

    public File composeBatch(List<PatentData> patents, List<File> claimFiles,
                             List<File> drawingFiles,
                             List<List<FigureUnit>> figureImages,
                             File outputPdf, File tempDir)
            throws IOException, DocumentException {
        if (patents == null || patents.isEmpty()) {
            throw new IllegalArgumentException("No hay patentes para componer.");
        }
        if (tempDir == null) {
            throw new IllegalArgumentException("Se requiere carpeta temporal para el lote.");
        }

        List<PDDocument> openSources = new ArrayList<>();
        try (PDDocument destDoc = new PDDocument()) {
            Cursor cursor = createInitialCursor(destDoc);

            for (int i = 0; i < patents.size(); i++) {
                PatentData patent = patents.get(i);
                if (i > 0) {
                    // Cada patente del lote empieza en su propia hoja
                    cursor.moveToNewPage(destDoc);
                }

                // 0) Banner/bandera al inicio de cada patente
                appendBannerImage(destDoc, cursor);

                // 1) Datos de patente — flujo continuo paginable
                appendPatentDataFlow(destDoc, cursor, patent);

                // 2) Reivindicaciones — solo para PI, MU, PC (tieneReivindicaciones)
                File claimFile = (claimFiles != null && i < claimFiles.size())
                        ? claimFiles.get(i) : null;
                if (patent.tieneReivindicaciones()) {
                    if (claimFile != null && claimFile.exists()) {
                        appendClaims(destDoc, cursor, claimFile, openSources);
                    } else {
                        LOG.warning("Lote: " + patent.getApplicationNumber()
                                + " [" + patent.getTipoPatenteAlias()
                                + "] requiere REIVINDICACIONES pero no hay archivo");
                    }
                }

                // 3) Dibujos — solo para DI (Diseños Industriales)
                if (patent.isDisenoIndustrial()) {
                    List<FigureUnit> figs = (figureImages != null && i < figureImages.size())
                            ? figureImages.get(i) : null;
                    if (figs != null && !figs.isEmpty()) {
                        appendFigures(destDoc, cursor, figs);
                    } else {
                        // Fallback: dibujos raw si no hay figuras pre-extraídas
                        File drawFile = (drawingFiles != null && i < drawingFiles.size())
                                ? drawingFiles.get(i) : null;
                        if (drawFile != null && drawFile.exists()) {
                            appendDrawings(destDoc, cursor, drawFile, openSources);
                        } else {
                            LOG.warning("Lote: " + patent.getApplicationNumber()
                                    + " [DI] requiere DIBUJOS pero no hay archivo");
                        }
                    }
                }
            }

            destDoc.save(outputPdf);
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            if (e instanceof DocumentException) {
                throw (DocumentException) e;
            }
            throw new IOException("Error componiendo lote continuo", e);
        } finally {
            for (PDDocument src : openSources) {
                try { src.close(); } catch (IOException ignore) { }
            }
        }

        return outputPdf;
    }

    /** Sobrecarga mantenida por compatibilidad (sin figuras pre-extraídas). */
    public File composeBatch(List<PatentData> patents, List<File> claimFiles,
                             List<File> drawingFiles,
                             File outputPdf, File tempDir)
            throws IOException, DocumentException {
        return composeBatch(patents, claimFiles, drawingFiles, null, outputPdf, tempDir);
    }

    /** Sobrecarga mantenida por compatibilidad (sin dibujos). */
    public File composeBatch(List<PatentData> patents, List<File> claimFiles,
                             File outputPdf, File tempDir)
            throws IOException, DocumentException {
        return composeBatch(patents, claimFiles, null, null, outputPdf, tempDir);
    }

    private Cursor createInitialCursor(PDDocument destDoc) {
        PDPage firstPage = new PDPage(PDRectangle.A4);
        destDoc.addPage(firstPage);
        return new Cursor(firstPage, BODY_TOP);
    }

    /**
     * Escribe los datos de una patente DIRECTAMENTE en el documento destino
     * como flujo continuo paginable. Cada campo se escribe línea por línea;
     * si no cabe en la página actual, se crea una nueva y se continúa.
     *
     * Esto reemplaza la generación de un PDF temporal con OpenPDF.
     * Resultado: los datos aprovechan el espacio libre de la página actual
     * y pueden partirse entre páginas sin desperdiciar espacio.
     */
    private void appendPatentDataFlow(PDDocument destDoc, Cursor cursor,
                                       PatentData patent) throws IOException {
        // Construir la lista de campos a escribir
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"Tipo de patente: ",
                defaultStr(patent.getTipoPatente(), "Patente")});
        fields.add(new String[]{"No. De Solicitud: ",
                defaultStr(patent.getApplicationNumber(), "\u2014")});
        fields.add(new String[]{"Fecha de solicitud: ",
                defaultStr(patent.getFechaSolicitudTexto(), "\u2014")});
        fields.add(new String[]{"Título de la Patente: ",
                defaultStr(patent.getTitulo(), "\u2014")});
        fields.add(new String[]{"Clasificación internacional de patente: ",
                defaultStr(patent.getClasificacionInternacional(), "")});
        fields.add(new String[]{"Solicitante: ",
                defaultStr(patent.getSolicitantesTexto(), "\u2014")});
        fields.add(new String[]{"País: ",
                defaultStr(patent.getPaisesTexto(), "\u2014")});
        fields.add(new String[]{"Fecha de Prioridad: ",
                defaultStr(patent.getFechaPrioridadTexto(), "")});
        fields.add(new String[]{"Representante/Apoderado: ",
                defaultStr(patent.getRepresentanteTexto(), "")});

        String resumen = patent.getResumen();
        String resumenTrimmed = (resumen != null) ? resumen.trim() : null;

        PDFont fontBold = PDType1Font.HELVETICA_BOLD;
        PDFont fontNormal = PDType1Font.HELVETICA;
        float fontSize = 10f;
        float leading = 13f;
        float usableWidth = PDRectangle.A4.getWidth()
                - PatentPdfGenerator.getMarginLeft()
                - PatentPdfGenerator.getMarginRight();
        float leftX = PatentPdfGenerator.getMarginLeft();

        for (String[] field : fields) {
            String label = field[0];
            String value = sanitize(field[1]);
            if (value.isEmpty() && !label.contains("Tipo") && !label.contains("Solicitud")
                    && !label.contains("Título") && !label.contains("Solicitante")) {
                continue; // Omitir campos opcionales vacíos
            }

            // Dividir el campo en líneas que quepan en el ancho disponible
            List<String[]> lines = wrapFieldLines(label, value,
                    fontBold, fontNormal, fontSize, usableWidth);

            for (String[] lineParts : lines) {
                // lineParts: [boldPart, normalPart]
                // Cada línea necesita 'leading' puntos de alto
                if (cursor.destY - leading < BODY_BOTTOM) {
                    cursor.moveToNewPage(destDoc);
                }

                try (PDPageContentStream cs = new PDPageContentStream(
                        destDoc, cursor.page, AppendMode.APPEND, true, true)) {
                    cs.beginText();
                    float currentX = leftX;
                    cs.newLineAtOffset(currentX, cursor.destY - leading);

                    if (lineParts[0] != null && !lineParts[0].isEmpty()) {
                        cs.setFont(fontBold, fontSize);
                        cs.setNonStrokingColor(0, 0, 0);
                        cs.showText(lineParts[0]);
                        currentX += fontBold.getStringWidth(lineParts[0]) / 1000f * fontSize;
                    }
                    if (lineParts[1] != null && !lineParts[1].isEmpty()) {
                        cs.setFont(fontNormal, fontSize);
                        cs.setNonStrokingColor(0, 0, 0);
                        if (lineParts[0] == null || lineParts[0].isEmpty()) {
                            // continuación sin etiqueta
                        }
                        cs.showText(lineParts[1]);
                    }
                    cs.endText();
                }
                cursor.destY -= leading;
            }
        }

        // ── Resumen justificado ──
        if (resumenTrimmed != null && !resumenTrimmed.isEmpty()) {
            writeJustifiedField(destDoc, cursor, "Resumen: ", resumenTrimmed,
                    fontBold, fontNormal, fontSize, leading, usableWidth, leftX);
        }
    }

    /**
     * Divide un campo (etiqueta + valor) en líneas que quepan en el ancho
     * disponible. La primera línea tiene la etiqueta bold + inicio del valor.
     * Las líneas siguientes solo tienen valor normal (continuación).
     */
    private List<String[]> wrapFieldLines(String label, String value,
                                           PDFont fontBold, PDFont fontNormal,
                                           float fontSize, float maxWidth) throws IOException {
        List<String[]> lines = new ArrayList<>();

        float labelWidth = fontBold.getStringWidth(label) / 1000f * fontSize;
        float availForValue = maxWidth - labelWidth;

        // Primera línea: label + tanto valor como quepa
        if (value.isEmpty()) {
            lines.add(new String[]{label, ""});
            return lines;
        }

        String remaining = value;
        boolean isFirstLine = true;

        while (!remaining.isEmpty()) {
            float availWidth = isFirstLine ? availForValue : maxWidth;
            String boldPart = isFirstLine ? label : null;

            // Buscar cuántos caracteres caben en esta línea
            String lineText = fitTextToWidth(remaining, fontNormal, fontSize, availWidth);

            lines.add(new String[]{
                    isFirstLine ? label : "",
                    lineText
            });

            remaining = remaining.substring(lineText.length()).trim();
            isFirstLine = false;
        }

        return lines;
    }

    /**
     * Escribe un campo con texto justificado usando PDFBox.
     * Distribuye espacio extra entre palabras para llenar el ancho disponible.
     */
    private void writeJustifiedField(PDDocument destDoc, Cursor cursor,
                                      String label, String value,
                                      PDFont fontBold, PDFont fontNormal,
                                      float fontSize, float leading,
                                      float usableWidth, float leftX) throws IOException {
        float labelWidth = fontBold.getStringWidth(label) / 1000f * fontSize;
        String remaining = sanitize(value);
        boolean isFirstLine = true;

        while (!remaining.isEmpty()) {
            float availWidth = isFirstLine ? usableWidth - labelWidth : usableWidth;
            String lineText = fitTextToWidth(remaining, fontNormal, fontSize, availWidth);
            String rest = remaining.substring(lineText.length()).trim();
            boolean isLastLine = rest.isEmpty();

            if (cursor.destY - leading < BODY_BOTTOM) {
                cursor.moveToNewPage(destDoc);
            }

            try (PDPageContentStream cs = new PDPageContentStream(
                    destDoc, cursor.page, AppendMode.APPEND, true, true)) {
                cs.beginText();
                float startX = leftX;
                cs.newLineAtOffset(startX, cursor.destY - leading);

                if (isFirstLine) {
                    cs.setFont(fontBold, fontSize);
                    cs.setNonStrokingColor(0, 0, 0);
                    cs.showText(label);
                }

                cs.setFont(fontNormal, fontSize);
                cs.setNonStrokingColor(0, 0, 0);

                // Justificar: distribuir espacio extra entre palabras (excepto última línea)
                if (!isLastLine && lineText.contains(" ")) {
                    String[] words = lineText.split(" ");
                    float textWidth = fontNormal.getStringWidth(lineText) / 1000f * fontSize;
                    float extraSpace = availWidth - textWidth;
                    float spacePerGap = (words.length > 1)
                            ? extraSpace / (words.length - 1) : 0;
                    float normalSpaceW = fontNormal.getStringWidth(" ") / 1000f * fontSize;
                    float wordSpacing = normalSpaceW + spacePerGap;

                    for (int w = 0; w < words.length; w++) {
                        cs.showText(words[w]);
                        if (w < words.length - 1) {
                            float wordW = fontNormal.getStringWidth(words[w]) / 1000f * fontSize;
                            float offset = wordW + wordSpacing;
                            if (w == 0 && isFirstLine) {
                                offset += labelWidth;
                            }
                            cs.newLineAtOffset(offset, 0);
                        }
                    }
                } else {
                    cs.showText(lineText);
                }

                cs.endText();
            }
            cursor.destY -= leading;
            remaining = rest;
            isFirstLine = false;
        }
    }

    /**
     * Determina la mayor cantidad de texto que cabe en el ancho dado.
     * Corta por palabras para no partir a media palabra.
     */
    private String fitTextToWidth(String text, PDFont font, float fontSize,
                                   float maxWidth) throws IOException {
        if (maxWidth <= 0) return "";

        float fullWidth = font.getStringWidth(text) / 1000f * fontSize;
        if (fullWidth <= maxWidth) {
            return text; // Cabe todo
        }

        // Buscar corte por palabras
        String[] words = text.split("(?<=\\s)");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.toString() + word;
            float w = font.getStringWidth(candidate) / 1000f * fontSize;
            if (w > maxWidth && line.length() > 0) {
                break;
            }
            line.append(word);
        }

        // Si no cabe ni una palabra, cortar por caracteres
        if (line.length() == 0) {
            for (int i = 1; i <= text.length(); i++) {
                float w = font.getStringWidth(text.substring(0, i)) / 1000f * fontSize;
                if (w > maxWidth) {
                    return text.substring(0, Math.max(1, i - 1));
                }
            }
            return text;
        }

        return line.toString();
    }

    /** Padding fijo (~2 cm) entre los datos del trámite y las reivindicaciones. */
    private static final float CLAIMS_TOP_PAD = 25f; //35f;

    private void appendClaims(PDDocument destDoc, Cursor cursor, File claimsPdf,
                              List<PDDocument> openSources) throws Exception {
        ClaimsFirstLiteralExtractor.ClaimRegion region = claimsExtractor.getFirstClaimRegion(claimsPdf);
        if (region == null) {
            return;
        }

        // ── Padding fijo de ~2 cm antes de las reivindicaciones ──
        // Si no cabe en la página actual, pasa el cursor a una nueva.
        ensureSpace(destDoc, cursor, CLAIMS_TOP_PAD + MIN_AVAILABLE_HEIGHT);
        cursor.destY -= CLAIMS_TOP_PAD;

        PDDocument claimsDoc = PDDocument.load(claimsPdf);
        openSources.add(claimsDoc);
        int totalSrcPages = claimsDoc.getNumberOfPages();
        int firstSrcPage = region.firstClaimPage;
        int lastSrcPage = (region.secondClaimPage >= 0)
                ? region.secondClaimPage
                : totalSrcPages - 1;

        // ── Detectar bands de header/footer (mismo flujo que el individual) ──
        // Analizar TODAS las páginas del PDF de claims para maximizar las señales
        // de repetición y limpiar encabezados/pies en cada página del rango.
        java.util.Map<Integer, float[]> hfBands =
                ClaimsHeaderFooterDetector.detectBands(claimsDoc, 0, totalSrcPages - 1);

        for (int srcIdx = firstSrcPage; srcIdx <= lastSrcPage && srcIdx < totalSrcPages; srcIdx++) {
            PDPage srcPage = claimsDoc.getPage(srcIdx);
            float srcH = srcPage.getMediaBox().getHeight();

            // ── Región visible — misma lógica que insertClaimContentStream (individual) ──
            float srcVisTop;
            boolean allowTopAdjust = false;

            if (srcIdx == firstSrcPage && region.firstClaimYDirAdj > 0) {
                if (region.headerYDirAdj > 0 && region.headerYDirAdj < region.firstClaimYDirAdj) {
                    srcVisTop = srcH - region.headerYDirAdj + 15f;
                } else {
                    srcVisTop = srcH - 15f;
                    allowTopAdjust = true;
                }
            } else {
                srcVisTop = srcH - 20f + 5f;
            }

            float srcVisBottom;
            boolean hasClaim2Boundary = (srcIdx == region.secondClaimPage && region.secondClaimYDirAdj > 0);
            if (hasClaim2Boundary) {
                srcVisBottom = srcH - region.secondClaimYDirAdj + 2f;
            } else {
                srcVisBottom = 20f;
            }

            // ── Aplicar bands de header/footer detectados ──
            float[] band = hfBands.get(srcIdx);
            if (band != null) {
                if (band[0] < srcVisTop)    srcVisTop = band[0];
                if (band[1] > srcVisBottom) srcVisBottom = band[1];
            }

            // ── Dividir en sub-rangos densos para comprimir gaps grandes ──
            java.util.List<float[]> denseSubRanges = ClaimsHeaderFooterDetector.detectDenseSubRanges(
                    claimsDoc, srcIdx, srcVisBottom, srcVisTop, 25f);
            if (denseSubRanges.isEmpty()) {
                denseSubRanges = java.util.Collections.singletonList(
                        new float[]{srcVisTop, srcVisBottom});
            }

            boolean isFirstSubRange = true;
            int lastSubRangeIdx = denseSubRanges.size() - 1;
            for (int i = 0; i < denseSubRanges.size(); i++) {
                float[] sr = denseSubRanges.get(i);
                float subTop = sr[0];
                float subBot = sr[1];
                if (subTop <= subBot + 0.5f) continue;

                boolean applyHeaderSpacing = (srcIdx == firstSrcPage) && isFirstSubRange;
                // El lockBottom solo aplica en el último sub-rango de la página
                // donde está la 2ª reivindicación (límite preciso).
                boolean lockBottom = hasClaim2Boundary && (i == lastSubRangeIdx);

                appendBodyRegion(destDoc, cursor, srcPage, subBot, subTop,
                        allowTopAdjust, applyHeaderSpacing, lockBottom);
                isFirstSubRange = false;
            }
        }
    }

    /** Overload sin flags — comportamiento original (ajuste libre). */
    private void appendBodyRegion(PDDocument destDoc, Cursor cursor, PDPage srcPage,
                                  float srcVisBottom, float srcVisTop) throws IOException {
        appendBodyRegion(destDoc, cursor, srcPage, srcVisBottom, srcVisTop, true, false, false);
    }

    /**
     * @param allowTopAdjust       si el pre-scan puede reducir srcVisTop al contenido real
     * @param applyHeaderSpacing   si debe aplicar padding mínimo entre datos y título REIVINDICACIONES
     */
    private void appendBodyRegion(PDDocument destDoc, Cursor cursor, PDPage srcPage,
                                  float srcVisBottom, float srcVisTop,
                                  boolean allowTopAdjust, boolean applyHeaderSpacing) throws IOException {
        appendBodyRegion(destDoc, cursor, srcPage, srcVisBottom, srcVisTop, allowTopAdjust, applyHeaderSpacing, false);
    }

    /**
     * @param allowTopAdjust       si el pre-scan puede reducir srcVisTop al contenido real
     * @param applyHeaderSpacing   si debe aplicar padding mínimo entre datos y título REIVINDICACIONES
     * @param lockBottom           si true, no ajustar srcVisBottom con pre-scan (claim2 ya provee límite preciso)
     */
    private void appendBodyRegion(PDDocument destDoc, Cursor cursor, PDPage srcPage,
                                  float srcVisBottom, float srcVisTop,
                                  boolean allowTopAdjust, boolean applyHeaderSpacing,
                                  boolean lockBottom) throws IOException {
        float srcH = srcPage.getMediaBox().getHeight();
        srcVisTop = Math.min(srcVisTop, srcH);
        srcVisBottom = Math.max(srcVisBottom, 0f);
        if (srcVisTop <= srcVisBottom) {
            return;
        }

        ContentStreamFragmenter.FragmentResult fullScan =
                ContentStreamFragmenter.extractByYRange(srcPage, srcVisBottom, srcVisTop);
        if (!fullScan.hasContent()) {
            return;
        }

        if (fullScan.minContentY >= fullScan.maxContentY) {
            return;
        }

        if (!lockBottom && fullScan.minContentY > srcVisBottom + 10f) {
            srcVisBottom = fullScan.minContentY - 15f;
        }
        if (allowTopAdjust && fullScan.maxContentY < srcVisTop - 10f) {
            srcVisTop = fullScan.maxContentY + 15f;
        }

        // Espaciado mínimo entre datos y título REIVINDICACIONES
        if (applyHeaderSpacing && fullScan.hasContent()
                && fullScan.maxContentY < srcVisTop) {
            float topGap = srcVisTop - fullScan.maxContentY;
            float MIN_HEADER_SPACING = 25f;
            if (topGap < MIN_HEADER_SPACING) {
                float extraPad = MIN_HEADER_SPACING - topGap;
                cursor.destY -= extraPad;
            }
        }

        float totalHeight = srcVisTop - srcVisBottom;
        float placed = 0f;

        while (placed < totalHeight - 0.5f) {
            float available = cursor.destY - BODY_BOTTOM;
            if (available < MIN_AVAILABLE_HEIGHT) {
                cursor.moveToNewPage(destDoc);
                available = cursor.destY - BODY_BOTTOM;
            }

            float fragmentHeight = Math.min(totalHeight - placed, available);
            float fragmentTop = srcVisTop - placed;
            float fragmentBottom = fragmentTop - fragmentHeight;

            ContentStreamFragmenter.FragmentResult fragment =
                    ContentStreamFragmenter.extractByYRange(srcPage, fragmentBottom, fragmentTop);

            if (fragment.hasContent()) {
                float offsetY = cursor.destY - fragmentTop;
                Map<COSName, COSName> remap =
                        ContentStreamFragmenter.copyAllResources(srcPage, cursor.page);
                if (!remap.isEmpty()) {
                    ContentStreamFragmenter.remapResourceNames(fragment.tokens, remap);
                }

                float clipBottom = Math.max(cursor.destY - fragmentHeight, BODY_BOTTOM);
                float clipTop = Math.min(cursor.destY + 15f, PAGE_HEIGHT);
                ContentStreamFragmenter.writeToPage(
                        destDoc, cursor.page, fragment.tokens, offsetY, clipBottom, clipTop);
            }

            cursor.destY -= fragmentHeight;
            placed += fragmentHeight;
        }
    }

    /**
     * Inserta las páginas del PDF de dibujos en flujo continuo,
     * usando la misma fragmentación que appendClaims/appendBodyRegion.
     */
    private void appendDrawings(PDDocument destDoc, Cursor cursor, File drawingsFile,
                                List<PDDocument> openSources) throws Exception {
        PDDocument drawDoc = PDDocument.load(drawingsFile);
        openSources.add(drawDoc);
        int pageCount = drawDoc.getNumberOfPages();
        for (int p = 0; p < pageCount; p++) {
            PDPage srcPage = drawDoc.getPage(p);
            float srcH = srcPage.getMediaBox().getHeight();
            float srcVisTop = srcH - 15f;
            float srcVisBottom = 15f;
            appendBodyRegion(destDoc, cursor, srcPage, srcVisBottom, srcVisTop);
        }
    }

    /**
     * Separador visual entre patentes: solo un pequeño espacio vertical.
     * La bandera ya actúa como separador visual.
     */
    private void appendSeparator(PDDocument destDoc, Cursor cursor)
            throws IOException {
        float sepHeight = 10f;
        ensureSpace(destDoc, cursor, sepHeight);
        cursor.destY -= sepHeight;
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Lote "Datos con hojas en blanco" ────────────────────────────
    // ══════════════════════════════════════════════════════════════════

    /**
     * Compone un lote donde cada patente incluye:
     *   1) Separador de bandera
     *   2) Datos de la patente (mismo estilo que el lote normal)
     *   3) El resto de la página queda vacío
     *   4) Una página adicional completamente en blanco
     *
     * NO incluye reivindicaciones, dibujos ni ningún otro contenido.
     */
    public File composeBlankSheetBatch(List<PatentData> patents,
                                       File outputPdf, File tempDir)
            throws IOException {
        if (patents == null || patents.isEmpty()) {
            throw new IllegalArgumentException("No hay patentes para componer.");
        }

        try (PDDocument destDoc = new PDDocument()) {
            for (int i = 0; i < patents.size(); i++) {
                PatentData patent = patents.get(i);

                // Cada patente comienza en una página nueva
                Cursor cursor = new Cursor(new PDPage(PDRectangle.A4), BODY_TOP);
                destDoc.addPage(cursor.page);

                // 1) Banner/bandera
                appendBannerImage(destDoc, cursor);

                // 2) Datos de patente
                appendPatentDataFlow(destDoc, cursor, patent);

                // 3) El resto de la página ya queda vacío

                // 4) Página adicional completamente en blanco
                destDoc.addPage(new PDPage(PDRectangle.A4));
            }

            destDoc.save(outputPdf);
        }

        return outputPdf;
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Lote de Diseños Industriales ────────────────────────────────
    // ══════════════════════════════════════════════════════════════════

    /**
     * Compone un lote continuo de Diseños Industriales.
     * Cada patente incluye datos + figuras con título "DIBUJOS" y etiquetas "FIGURA N".
     *
     * @param patents      lista de PatentData de cada diseño
     * @param figureImages lista paralela: cada elemento es lista de byte[] (imágenes PNG extraídas)
     * @param outputPdf    archivo de salida
     * @param tempDir      carpeta temporal
     */
    public File composeDesignBatch(List<PatentData> patents,
                                    List<List<FigureUnit>> figureUnits,
                                    File outputPdf, File tempDir)
            throws IOException, DocumentException {
        if (patents == null || patents.isEmpty()) {
            throw new IllegalArgumentException("No hay patentes para componer.");
        }

        try (PDDocument destDoc = new PDDocument()) {
            Cursor cursor = createInitialCursor(destDoc);

            for (int i = 0; i < patents.size(); i++) {
                PatentData patent = patents.get(i);
                if (i > 0) {
                    // Cada diseño del lote empieza en su propia hoja
                    cursor.moveToNewPage(destDoc);
                }

                // 0) Banner al inicio de cada patente
                appendBannerImage(destDoc, cursor);

                // 1) Datos de patente
                appendPatentDataFlow(destDoc, cursor, patent);

                // 2) Figuras con etiquetas
                List<FigureUnit> figs = (figureUnits != null && i < figureUnits.size())
                        ? figureUnits.get(i) : null;
                if (figs != null && !figs.isEmpty()) {
                    appendFigures(destDoc, cursor, figs);
                }
            }

            destDoc.save(outputPdf);
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            if (e instanceof DocumentException) throw (DocumentException) e;
            throw new IOException("Error componiendo lote de diseños", e);
        }

        return outputPdf;
    }

    /**
     * Coloca figuras de un Diseño Industrial: título "DIBUJOS" + una figura por página.
     *
     * Reglas idénticas al flujo individual:
     *   - Cada figura usa su tamaño físico explícito (FigureUnit.widthPt/heightPt),
     *     reducido solo si excede el área útil A4.
     *   - La primera figura + título "DIBUJOS" se intentan colocar junto al
     *     encabezado de datos; si no caben, se desplazan a una nueva página.
     *   - Cada figura subsiguiente va centrada verticalmente en su propia hoja.
     *   - El título "DIBUJOS" lleva un padding superior de ~2 cm.
     */
    private void appendFigures(PDDocument destDoc, Cursor cursor,
                               List<FigureUnit> figureUnits) throws IOException {
        if (figureUnits == null || figureUnits.isEmpty()) return;

        float marginL = PatentPdfGenerator.getMarginLeft();
        float marginR = PatentPdfGenerator.getMarginRight();
        float usableW = PDRectangle.A4.getWidth() - marginL - marginR;
        float usableH = BODY_TOP - BODY_BOTTOM;

        final float sectionTitleTopPad = 25f; //29f; //35f;  // ~2 cm antes del título "DIBUJOS"
        final float sectionTitleH = 22f;

        // ── Pre-crear imágenes y calcular tamaño físico (explícito en FigureUnit) ──
        int n = figureUnits.size();
        List<PDImageXObject> pdfImages = new ArrayList<>();
        float[] sW = new float[n];
        float[] sH = new float[n];
        for (int i = 0; i < n; i++) {
            FigureUnit fu = figureUnits.get(i);
            PDImageXObject img = PDImageXObject.createFromByteArray(
                    destDoc, fu.imageBytes, "figura_" + (i + 1));
            pdfImages.add(img);
            float w = fu.widthPt;
            float h = fu.heightPt;
            float scale = 1f;
            if (w > usableW)          scale = Math.min(scale, usableW / w);
            if (h * scale > usableH)  scale = Math.min(scale, usableH / h);
            sW[i] = w * scale;
            sH[i] = h * scale;
        }

        // ── ¿Cabe título + primera figura junto al encabezado? ──
        float pageH = PDRectangle.A4.getHeight();
        float firstBlockTotal = sectionTitleTopPad + sectionTitleH + sH[0];
        if (cursor.destY - BODY_BOTTOM >= firstBlockTotal) {
            cursor.destY -= sectionTitleTopPad;
        } else {
            cursor.moveToNewPage(destDoc);
            cursor.destY = pageH - sectionTitleTopPad;
        }

        // ── Título "DIBUJOS" ──
        try (PDPageContentStream cs = new PDPageContentStream(
                destDoc, cursor.page, AppendMode.APPEND, true, true)) {
            String secTitle = "DIBUJOS";
            float secTitleW = PDType1Font.HELVETICA_BOLD.getStringWidth(secTitle) / 1000f * 12f;
            float secTitleX = marginL + (usableW - secTitleW) / 2f;
            float secTitleY = cursor.destY - 15f;
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f);
            cs.setNonStrokingColor(0, 0, 0);
            cs.newLineAtOffset(secTitleX, secTitleY);
            cs.showText(secTitle);
            cs.endText();
        }
        cursor.destY -= sectionTitleH;

        // ── Clamp defensivo: primera figura debe caber bajo el título ──
        float availFirst = cursor.destY - BODY_BOTTOM;
        if (sH[0] > availFirst && availFirst > 50f) {
            float fit = availFirst / sH[0];
            sW[0] *= fit;
            sH[0] = availFirst;
        }

        // ── Una figura por página, sin etiqueta individual ──
        float lastBlockBottom = cursor.destY;
        for (int i = 0; i < n; i++) {
            float blockTop;
            if (i == 0) {
                blockTop = cursor.destY;
            } else {
                cursor.moveToNewPage(destDoc);
                blockTop = BODY_BOTTOM + (usableH + sH[i]) / 2f;
            }

            float imgX = marginL + (usableW - sW[i]) / 2f;
            float imgY = blockTop - sH[i];

            try (PDPageContentStream cs = new PDPageContentStream(
                    destDoc, cursor.page, AppendMode.APPEND, true, true)) {
                cs.drawImage(pdfImages.get(i), imgX, imgY, sW[i], sH[i]);
            }

            lastBlockBottom = blockTop - sH[i];
        }
        cursor.destY = lastBlockBottom;
    }

    /**
     * Dibuja la imagen de bandera/banner SENADI (separador.png) al inicio
     * de cada patente — idéntico al flujo individual.
     */
    private void appendBannerImage(PDDocument destDoc, Cursor cursor)
            throws IOException {
        if (separatorImageBytes == null || separatorImageBytes.length == 0) {
            return;
        }
        float bannerH = 14f;
        float spacerAfter = 6f;
        float needed = bannerH + spacerAfter;
        ensureSpace(destDoc, cursor, needed);

        PDImageXObject img = PDImageXObject.createFromByteArray(
                destDoc, separatorImageBytes, "separador");
        float usableW = PDRectangle.A4.getWidth()
                - PatentPdfGenerator.getMarginLeft()
                - PatentPdfGenerator.getMarginRight();
        float scale = bannerH / img.getHeight();
        float scaledW = Math.min(img.getWidth() * scale * 1.3f, usableW);
        float imgX = PatentPdfGenerator.getMarginLeft()
                + (usableW - scaledW) / 2f;
        float imgY = cursor.destY - bannerH;

        try (PDPageContentStream cs = new PDPageContentStream(
                destDoc, cursor.page, AppendMode.APPEND, true, true)) {
            cs.drawImage(img, imgX, imgY, scaledW, bannerH);
        }

        cursor.destY -= needed;
    }

    private void ensureSpace(PDDocument destDoc, Cursor cursor, float requiredHeight) {
        if (cursor.destY - BODY_BOTTOM < requiredHeight) {
            cursor.moveToNewPage(destDoc);
        }
    }

    private String safe(String value) {
        return value != null && !value.trim().isEmpty() ? value.trim() : "Patente";
    }

    private String defaultStr(String val, String def) {
        return (val != null && !val.trim().isEmpty()) ? val.trim() : def;
    }

    private String truncate(String value, int maxLen) {
        String trimmed = value.trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen - 3) + "..." : trimmed;
    }

    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c <= 0xFF) {
                sb.append(c == '\u2014' ? '-' : c);
            } else if (c == '\t') {
                sb.append("    ");
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static final class Cursor {
        private PDPage page;
        private float destY;

        private Cursor(PDPage page, float destY) {
            this.page = page;
            this.destY = destY;
        }

        private void moveToNewPage(PDDocument destDoc) {
            page = new PDPage(PDRectangle.A4);
            destDoc.addPage(page);
            destY = BODY_TOP;
        }
    }
}
