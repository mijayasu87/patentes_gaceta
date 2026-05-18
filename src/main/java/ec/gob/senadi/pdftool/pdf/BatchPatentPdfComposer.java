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
                             List<List<byte[]>> figureImages,
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
                    appendSeparator(destDoc, cursor);
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
                    List<byte[]> figs = (figureImages != null && i < figureImages.size())
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

    private void appendClaims(PDDocument destDoc, Cursor cursor, File claimsPdf,
                              List<PDDocument> openSources) throws Exception {
        ClaimsFirstLiteralExtractor.ClaimRegion region = claimsExtractor.getFirstClaimRegion(claimsPdf);
        if (region == null) {
            return;
        }

        PDDocument claimsDoc = PDDocument.load(claimsPdf);
        openSources.add(claimsDoc);
        int totalSrcPages = claimsDoc.getNumberOfPages();
        int firstSrcPage = region.firstClaimPage;
        int lastSrcPage = (region.secondClaimPage >= 0)
                ? region.secondClaimPage
                : totalSrcPages - 1;

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

            appendBodyRegion(destDoc, cursor, srcPage, srcVisBottom, srcVisTop,
                    allowTopAdjust, srcIdx == firstSrcPage, hasClaim2Boundary);
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
                                    List<List<byte[]>> figureImages,
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
                    appendSeparator(destDoc, cursor);
                }

                // 0) Banner al inicio de cada patente
                appendBannerImage(destDoc, cursor);

                // 1) Datos de patente
                appendPatentDataFlow(destDoc, cursor, patent);

                // 2) Figuras con etiquetas
                List<byte[]> figs = (figureImages != null && i < figureImages.size())
                        ? figureImages.get(i) : null;
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
     * Coloca figuras extraídas con título "DIBUJOS" y etiquetas "FIGURA N".
     * Layout adaptativo: 1 columna para ≤2 figuras, 2 columnas para >2.
     */
    private void appendFigures(PDDocument destDoc, Cursor cursor,
                               List<byte[]> figureImages) throws IOException {
        if (figureImages == null || figureImages.isEmpty()) return;

        float marginL = PatentPdfGenerator.getMarginLeft();
        float marginR = PatentPdfGenerator.getMarginRight();
        float usableW = PDRectangle.A4.getWidth() - marginL - marginR;

        boolean fewFigures = figureImages.size() <= 2;
        float labelH = 14f;
        float rowGap = 8f;
        float colGap = 12f;
        float cellW = fewFigures ? usableW : (usableW - colGap) / 2f;
        float maxCellImgH = fewFigures ? 280f : 180f;

        // ── Pre-crear las imágenes PDFBox ──
        List<PDImageXObject> pdfImages = new ArrayList<>();
        for (int i = 0; i < figureImages.size(); i++) {
            pdfImages.add(PDImageXObject.createFromByteArray(
                    destDoc, figureImages.get(i), "figura_" + (i + 1)));
        }

        // ── Título "DIBUJOS" ──
        float sectionTitleH = 22f;
        ensureSpace(destDoc, cursor, sectionTitleH + 80f);

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

        // ── Colocar figuras ──
        int fi = 0;
        int figNum = 1;
        int colsPerRow = fewFigures ? 1 : 2;

        while (fi < figureImages.size()) {
            int rowCount = Math.min(colsPerRow, figureImages.size() - fi);

            // Calcular tamaño escalado
            float rowImgH = 0;
            float[] sW = new float[rowCount];
            float[] sH = new float[rowCount];

            for (int r = 0; r < rowCount; r++) {
                PDImageXObject img = pdfImages.get(fi + r);
                float iw = img.getWidth();
                float ih = img.getHeight();
                float scale = Math.min(cellW / iw, maxCellImgH / ih);
                if (scale > 1f) scale = 1f;
                sW[r] = iw * scale;
                sH[r] = ih * scale;
                rowImgH = Math.max(rowImgH, sH[r]);
            }

            float neededH = labelH + rowImgH + rowGap;
            float available = cursor.destY - BODY_BOTTOM;

            if (available < labelH + 50f) {
                cursor.moveToNewPage(destDoc);
                available = cursor.destY - BODY_BOTTOM;
            }

            if (neededH > available) {
                float maxH = available - labelH - rowGap;
                if (maxH < 50f) {
                    cursor.moveToNewPage(destDoc);
                    available = cursor.destY - BODY_BOTTOM;
                    maxH = available - labelH - rowGap;
                }
                for (int r = 0; r < rowCount; r++) {
                    if (sH[r] > maxH) {
                        float fitScale = maxH / sH[r];
                        sW[r] *= fitScale;
                        sH[r] = maxH;
                    }
                }
                rowImgH = Math.min(rowImgH, maxH);
            }

            // Dibujar cada figura de la fila
            for (int r = 0; r < rowCount; r++) {
                String label = "FIGURA " + figNum;

                float colX;
                if (rowCount == 2) {
                    colX = marginL + r * (cellW + colGap);
                } else {
                    colX = marginL + (usableW - cellW) / 2f;
                }

                float lblW = PDType1Font.HELVETICA_BOLD.getStringWidth(label) / 1000f * 10f;
                float lblX = colX + (cellW - lblW) / 2f;
                float lblY = cursor.destY - labelH + 2f;
                float imgX = colX + (cellW - sW[r]) / 2f;
                float imgY = cursor.destY - labelH - sH[r];

                try (PDPageContentStream cs = new PDPageContentStream(
                        destDoc, cursor.page, AppendMode.APPEND, true, true)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 10f);
                    cs.setNonStrokingColor(0, 0, 0);
                    cs.newLineAtOffset(lblX, lblY);
                    cs.showText(label);
                    cs.endText();
                    cs.drawImage(pdfImages.get(fi + r), imgX, imgY, sW[r], sH[r]);
                }
                figNum++;
            }

            cursor.destY -= (labelH + rowImgH + rowGap);
            fi += rowCount;
        }
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
