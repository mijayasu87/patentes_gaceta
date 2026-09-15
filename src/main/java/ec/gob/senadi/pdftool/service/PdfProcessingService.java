package ec.gob.senadi.pdftool.service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import ec.gob.senadi.pdftool.model.PatentData;
import ec.gob.senadi.pdftool.pdf.BatchPatentPdfComposer;
import ec.gob.senadi.pdftool.pdf.ClaimsFirstLiteralExtractor;
import ec.gob.senadi.pdftool.pdf.ClaimsHeaderFooterDetector;
import ec.gob.senadi.pdftool.pdf.ContentStreamFragmenter;
import ec.gob.senadi.pdftool.pdf.FigureUnit;
import ec.gob.senadi.pdftool.pdf.FiguresProcessor;
import ec.gob.senadi.pdftool.pdf.FormularioProcessor;
import ec.gob.senadi.pdftool.pdf.PatentPdfGenerator;

public class PdfProcessingService {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(PdfProcessingService.class.getName());

    private final ClaimsFirstLiteralExtractor claimsExtractor = new ClaimsFirstLiteralExtractor();
    private final BatchPatentPdfComposer batchComposer = new BatchPatentPdfComposer();
    private final FiguresProcessor figuresProcessor = new FiguresProcessor();
    private final FormularioProcessor formProcessor = new FormularioProcessor();
    private final PatentPdfGenerator patentPdfGen = new PatentPdfGenerator();

    /**
     * Genera el PDF final a partir de datos de la BD.
     *
     * Arquitectura: fragmenta el content stream de las reivindicaciones a nivel
     * de operadores PDF. Para cada bloque de texto BT...ET, rastrea la posición Y
     * y solo incluye las líneas cuya Y cae dentro del rango visible.
     *
     * Resultado:
     *   - PDF vectorial, texto seleccionable
     *   - Sin contenido fantasma (el texto fuera de rango se elimina físicamente)
     *   - Sin imágenes rasterizadas
     *   - Fuentes originales copiadas del PDF fuente (sin re-rendering)
     *   - La reivindicación empieza justo después de los datos (sin espacio en blanco)
     *   - Continúa en páginas nuevas cuando no cabe más
     */
    public File processPatentFromDB(PatentData data, File reivindicacionesPdf,
                                     File dibujosPdf, File tempDir) throws Exception {
        ensureDir(tempDir);

        File tempCombined  = new File(tempDir, "_temp_data_claims.pdf");

        try {
            // 1) Generar página de datos con OpenPDF y CERRAR
            LOG.info("processPatentFromDB: Generando página de datos...");
            byte[] separatorImage = findSeparatorImageBytes();
            PatentPdfGenerator.GenerateResult dataResult =
                    patentPdfGen.generateDocument(data, separatorImage, tempCombined);
            float remainingY = dataResult.getRemainingY();
            dataResult.document.close();
            LOG.info("processPatentFromDB: página de datos cerrada, espacio restante = " + remainingY + "pt");

            // 2) Decidir sección según TIPO DE PATENTE (no por archivo)
            if (data.tieneReivindicaciones()) {
                // PI, PC, MU → van por REIVINDICACIONES
                if (reivindicacionesPdf != null && reivindicacionesPdf.exists()) {
                    try {
                        ClaimsFirstLiteralExtractor.ClaimRegion region =
                                claimsExtractor.getFirstClaimRegion(reivindicacionesPdf);

                        if (region != null) {
                            LOG.info("processPatentFromDB: [" + data.getTipoPatenteAlias()
                                    + "] Claim1 pag=" + region.firstClaimPage
                                    + " Y=" + region.firstClaimYDirAdj
                                    + ", Claim2 pag=" + region.secondClaimPage
                                    + " Y=" + region.secondClaimYDirAdj
                                    + ", Header Y=" + region.headerYDirAdj);
                            insertClaimContentStream(tempCombined, reivindicacionesPdf, region, remainingY);
                        }
                    } catch (Exception e) {
                        LOG.warning("Error insertando reivindicación: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    LOG.warning("processPatentFromDB: tipo " + data.getTipoPatenteAlias()
                            + " requiere REIVINDICACIONES pero no se encontró archivo");
                }
            } else if (data.isDisenoIndustrial()) {
                // DI → va exclusivamente por DIBUJOS
                if (dibujosPdf != null && dibujosPdf.exists()) {
                    try {
                        insertFiguresContinuous(tempCombined, dibujosPdf, remainingY);
                        LOG.info("processPatentFromDB: [DI] dibujos insertados en continuidad");
                    } catch (Exception e) {
                        LOG.warning("Error insertando dibujos: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    LOG.warning("processPatentFromDB: Diseño Industrial requiere DIBUJOS pero no se encontró archivo");
                }
            } else {
                LOG.warning("processPatentFromDB: tipo desconocido patentTypeId="
                        + data.getPatentTypeId() + ", no se inserta reivindicaciones ni dibujos");
            }

            LOG.info("processPatentFromDB: PDF final → " + tempCombined.length() + " bytes");

            // 4) Copiar al archivo de salida
            File outputPdf = new File(tempDir, "RESULTADO_FINAL.pdf");
            java.nio.file.Files.copy(tempCombined.toPath(), outputPdf.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return outputPdf;
        } finally {
            if (tempCombined.exists()) tempCombined.delete();
        }
    }

    /**
     * Inserta la 1ª reivindicación manipulando el content stream con PDFBox.
     *
     * Extrae operadores PDF del fuente (sin modificar el PDF original),
     * filtra por rango Y, y los escribe en el destino con traslación.
     * Se preservan TODOS los operadores gráficos (fills, paths, colores)
     * para mantener el formato exacto del original.
     */
    private void insertClaimContentStream(
            File destPdf, File claimsPdf,
            ClaimsFirstLiteralExtractor.ClaimRegion region,
            float remainingY) throws Exception {

        byte[] destBytes = java.nio.file.Files.readAllBytes(destPdf.toPath());
        byte[] claimsBytes = java.nio.file.Files.readAllBytes(claimsPdf.toPath());

        try (PDDocument destDoc = PDDocument.load(destBytes);
             PDDocument claimsDoc = PDDocument.load(claimsBytes)) {

            int totalSrcPages = claimsDoc.getNumberOfPages();
            int firstSrcPage = region.firstClaimPage;
            int lastSrcPage = (region.secondClaimPage >= 0)
                    ? region.secondClaimPage
                    : totalSrcPages - 1;

            float destBodyTop    = 842f - 50f;  // 792
            float destBodyBottom = 60f;

            float destY = destBodyBottom + remainingY;
            PDPage currentDestPage = destDoc.getPage(destDoc.getNumberOfPages() - 1);

            // ── Padding fijo (~2 cm) entre los datos del trámite y las reivindicaciones ──
            final float CLAIMS_TOP_PAD = 25f; //29f;//35f; //57f;
            destY -= CLAIMS_TOP_PAD;
            if (destY - destBodyBottom < 30f) {
                currentDestPage = new PDPage(PDRectangle.A4);
                destDoc.addPage(currentDestPage);
                destY = destBodyTop;
            }

            LOG.info("insertClaimContentStream: srcPages " + firstSrcPage + "-" + lastSrcPage
                    + ", destY=" + destY + ", available=" + (destY - destBodyBottom));

            // ── Detectar bands de header/footer ──
            // Analizar TODAS las páginas del PDF de claims (no solo las que se
            // extraen) para maximizar las señales de repetición: si una sola
            // página de la 1ª reivindicación contiene un header repetido, sin
            // mirar las demás páginas no podríamos detectarlo.
            Map<Integer, float[]> hfBands = ClaimsHeaderFooterDetector.detectBands(
                    claimsDoc, 0, totalSrcPages - 1);

            for (int srcIdx = firstSrcPage; srcIdx <= lastSrcPage && srcIdx < totalSrcPages; srcIdx++) {
                PDPage srcPage = claimsDoc.getPage(srcIdx);
                float srcH = srcPage.getMediaBox().getHeight();

                // ── Región visible en coordenadas PDF (Y desde abajo) ──
                float srcVisTop, srcVisBottom;

                // ── Controlar si el pre-scan puede ajustar el top en primera página ──
                boolean allowTopAdjust = false;

                if (srcIdx == firstSrcPage && region.firstClaimYDirAdj > 0) {
                    // Empezar extracción desde el título "REIVINDICACIONES" si existe,
                    // o con margen generoso como fallback para no perder contenido.
                    // DirAdj Y (desde arriba) → PDF Y (desde abajo): pdfY = srcH - dirAdjY
                    if (region.headerYDirAdj > 0 && region.headerYDirAdj < region.firstClaimYDirAdj) {
                        // Usar posición del título con margen arriba
                        srcVisTop = srcH - region.headerYDirAdj + 15f;
                    } else {
                        // Sin título encontrado: margen amplio arriba del claim
                        // para capturar REIVINDICACIONES si existe.
                        // El pre-scan recortará si hay mucho espacio vacío.
                        srcVisTop = srcH - 15f;
                        allowTopAdjust = true;
                    }
                } else {
                    srcVisTop = srcH - 20f + 5f;
                }

                // Límite inferior: posición de la 2ª reiv + margen mínimo.
                // +2pt basta para excluir "2." (el check usa > estricto)
                // sin perder la última línea de la reiv 1 que puede estar a ~12pt.
                float claim2PdfY = -1f;
                if (srcIdx == region.secondClaimPage && region.secondClaimYDirAdj > 0) {
                    claim2PdfY = srcH - region.secondClaimYDirAdj;
                    srcVisBottom = claim2PdfY + 2f;
                } else {
                    srcVisBottom = 20f;
                }

                // ── Aplicar bands de header/footer detectados ──
                float[] band = hfBands.get(srcIdx);
                if (band != null) {
                    // band[0] = topAllowedPdfY, band[1] = bottomAllowedPdfY
                    if (band[0] < srcVisTop)      srcVisTop = band[0];
                    if (band[1] > srcVisBottom)   srcVisBottom = band[1];
                }

                srcVisTop = Math.min(srcVisTop, srcH);
                srcVisBottom = Math.max(srcVisBottom, 0f);
                if (srcVisTop <= srcVisBottom) continue;

                // ── Pre-scan: detectar contenido real para ajustar bounds ──
                ContentStreamFragmenter.FragmentResult fullScan =
                        ContentStreamFragmenter.extractByYRange(srcPage, srcVisBottom, srcVisTop);
                if (fullScan.hasContent() && fullScan.maxContentY >= fullScan.minContentY) {
                    // Ajuste inferior: solo cuando NO tenemos posición precisa de claim 2.
                    // Cuando claim2 ya provee un límite preciso, no ajustar: el
                    // minContentY ignora texto con X < 80 (MARGIN_X) y podría subir
                    // el límite excluyendo la última línea de la reiv 1 si su X es bajo.
                    if (claim2PdfY < 0 && fullScan.minContentY > srcVisBottom + 10f) {
                        srcVisBottom = fullScan.minContentY - 15f;
                    }
                    if ((srcIdx != firstSrcPage || allowTopAdjust)
                            && fullScan.maxContentY < srcVisTop - 10f) {
                        srcVisTop = fullScan.maxContentY + 15f;
                    }
                }

                float totalHeight = srcVisTop - srcVisBottom;

                // ── Espaciado mínimo entre datos y título REIVINDICACIONES ──
                // Si el gap entre srcVisTop y el contenido más alto es menor
                // al mínimo visual, desplazar destY hacia abajo para compensar.
                if (srcIdx == firstSrcPage && fullScan.hasContent()
                        && fullScan.maxContentY < srcVisTop) {
                    float topGap = srcVisTop - fullScan.maxContentY;
                    float MIN_HEADER_SPACING = 25f;
                    if (topGap < MIN_HEADER_SPACING) {
                        float extraPad = MIN_HEADER_SPACING - topGap;
                        destY -= extraPad;
                    }
                }

                LOG.info("insertClaimContentStream: srcPage " + srcIdx
                        + " visTop=" + srcVisTop + " visBot=" + srcVisBottom
                        + " height=" + totalHeight);

                // ── Dividir el rango en sub-rangos densos (comprime gaps grandes) ──
                // Sirve para que un salto de página del PDF original no genere
                // un hueco vertical en el PDF final: el texto fluye continuo.
                List<float[]> denseSubRanges = ClaimsHeaderFooterDetector.detectDenseSubRanges(
                        claimsDoc, srcIdx, srcVisBottom, srcVisTop, 25f);
                if (denseSubRanges.isEmpty()) {
                    denseSubRanges = java.util.Collections.singletonList(
                            new float[]{srcVisTop, srcVisBottom});
                }
                LOG.info("insertClaimContentStream: srcPage " + srcIdx
                       + " → " + denseSubRanges.size() + " sub-rangos densos");

                float pageMaxH = destBodyTop - destBodyBottom;

                for (float[] sr : denseSubRanges) {
                    float subTop = sr[0];
                    float subBot = sr[1];
                    if (subTop <= subBot + 0.5f) continue;

                    // ── Pre-detectar rangos Y de imágenes/fórmulas en el sub-rango ──
                    List<float[]> imageRanges = ContentStreamFragmenter.extractXObjectYRanges(
                            srcPage, subBot, subTop);

                    float subHeight = subTop - subBot;
                    float placed = 0f;

                    while (placed < subHeight - 0.5f) {
                        float available = destY - destBodyBottom;

                        if (available < 30f) {
                            currentDestPage = new PDPage(PDRectangle.A4);
                            destDoc.addPage(currentDestPage);
                            destY = destBodyTop;
                            available = destBodyTop - destBodyBottom;
                        }

                        float fragH = Math.min(subHeight - placed, available);
                        float fragTop = subTop - placed;
                        float fragBot = fragTop - fragH;

                        // ── Ajustar fragBot para no cortar una imagen/fórmula ──
                        boolean forceNewPage = false;
                        for (float[] img : imageRanges) {
                            float imgBot = img[0];
                            float imgTop = img[1];
                            if (imgBot >= fragTop || imgTop <= fragBot) continue;
                            if (fragBot > imgBot && fragBot < imgTop) {
                                float reqH    = fragTop - imgBot;
                                float beforeH = fragTop - imgTop;
                                if (reqH <= available) {
                                    fragBot = imgBot;
                                    fragH = reqH;
                                } else if (beforeH >= 30f) {
                                    fragBot = imgTop;
                                    fragH = beforeH;
                                } else if ((imgTop - imgBot) > pageMaxH) {
                                    fragBot = imgBot;
                                    fragH = reqH;
                                } else {
                                    forceNewPage = true;
                                    break;
                                }
                            }
                        }
                        if (forceNewPage) {
                            currentDestPage = new PDPage(PDRectangle.A4);
                            destDoc.addPage(currentDestPage);
                            destY = destBodyTop;
                            continue;
                        }

                        ContentStreamFragmenter.FragmentResult fragment =
                                ContentStreamFragmenter.extractByYRange(srcPage, fragBot, fragTop);

                        if (fragment.hasContent()) {
                            float offsetY = destY - fragTop;
                            java.util.Map<org.apache.pdfbox.cos.COSName, org.apache.pdfbox.cos.COSName> remap =
                                    ContentStreamFragmenter.copyAllResources(srcPage, currentDestPage);
                            if (!remap.isEmpty()) {
                                ContentStreamFragmenter.remapResourceNames(fragment.tokens, remap);
                            }
                            float clipBottom = Math.max(destY - fragH - 8f, destBodyBottom - 5f);
                            float clipTop    = Math.min(destY + 20f, 842f);
                            ContentStreamFragmenter.writeToPage(
                                    destDoc, currentDestPage, fragment.tokens, offsetY,
                                    clipBottom, clipTop);
                            destY -= fragH;
                        }
                        // Si el fragmento no tiene contenido, no movemos destY:
                        // el espacio vacío del fuente se comprime en el destino.
                        placed += fragH;
                    }
                }
            }

            LOG.info("insertClaimContentStream: completado, destY final = " + destY);
            destDoc.save(destPdf);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Inserción continua de dibujos/figuras (Diseños Industriales) ─
    // ══════════════════════════════════════════════════════════════════

    private static final Pattern FIGURE_LABEL_RE =
            Pattern.compile("(?i)(figura|fig\\.?|figure)\\s*\\d+");

    /**
     * Inserta las figuras del PDF de dibujos en el documento destino.
     *
     * Reglas de colocación (Diseños Industriales):
     *   - Cada figura se presenta en su tamaño físico original (renderizado
     *     a 200 DPI → pt). Solo se reduce si excede el área útil A4.
     *   - Una figura por página: cada dibujo en su propia hoja.
     *   - La primera figura (junto con el título de sección "DIBUJOS")
     *     se intenta colocar en la página del encabezado de datos si cabe
     *     en el espacio remanente; si no cabe, se desplaza a la siguiente página.
     *   - Cada figura lleva su etiqueta "FIGURA N" centrada encima.
     *
     * @param remainingY espacio vertical disponible (en pt) debajo de los datos
     */
    private void insertFiguresContinuous(File destPdf, File dibujosPdf,
                                          float remainingY) throws Exception {
        byte[] destBytes = java.nio.file.Files.readAllBytes(destPdf.toPath());

        try (PDDocument destDoc = PDDocument.load(destBytes);
             PDDocument srcDoc = PDDocument.load(dibujosPdf)) {

            List<FigureUnit> figures = extractFigureImagesByPage(srcDoc);
            if (figures.isEmpty()) {
                LOG.warning("insertFiguresContinuous: no se encontraron imágenes en el PDF de dibujos");
                return;
            }
            LOG.info("insertFiguresContinuous: " + figures.size() + " figuras encontradas");

            float pageW = PDRectangle.A4.getWidth();
            float pageH = PDRectangle.A4.getHeight();
            float marginL = PatentPdfGenerator.getMarginLeft();
            float marginR = PatentPdfGenerator.getMarginRight();
            float marginT = PatentPdfGenerator.getMarginTop();
            float marginB = PatentPdfGenerator.getMarginBottom();
            float usableW = pageW - marginL - marginR;
            float bodyTop = pageH - marginT;
            float bodyBottom = marginB;
            float usableH = bodyTop - bodyBottom;

            final float sectionTitleTopPad = 25f; //29f; //57f;  // ~2 cm antes del título "DIBUJOS"
            final float sectionTitleH = 22f;

            // ── Pre-crear imágenes PDFBox y calcular tamaño final ──
            // Tamaño físico original (figuras.widthPt/heightPt), reducido a la
            // baja si excede el área útil de una página completa.
            List<PDImageXObject> pdfImages = new ArrayList<>();
            float[] sW = new float[figures.size()];
            float[] sH = new float[figures.size()];
            for (int i = 0; i < figures.size(); i++) {
                FigureUnit fd = figures.get(i);
                pdfImages.add(PDImageXObject.createFromByteArray(
                        destDoc, fd.imageBytes, "figura_" + (i + 1)));
                float w = fd.widthPt;
                float h = fd.heightPt;
                float scale = 1f;
                if (w > usableW)            scale = Math.min(scale, usableW / w);
                if (h * scale > usableH)    scale = Math.min(scale, usableH / h);
                sW[i] = w * scale;
                sH[i] = h * scale;
            }

            // ── Decidir si la primera figura cabe junto al encabezado ──
            PDPage currentPage = destDoc.getPage(destDoc.getNumberOfPages() - 1);
            float destY = bodyBottom + remainingY;
            float firstBlockTotal = sectionTitleTopPad + sectionTitleH + sH[0];
            boolean firstFitsAfterHeader = (destY - bodyBottom) >= firstBlockTotal;

            if (firstFitsAfterHeader) {
                destY -= sectionTitleTopPad;
                LOG.info("insertFiguresContinuous: primera figura cabe junto al encabezado (remainingY="
                       + remainingY + ", necesario=" + firstBlockTotal + ")");
            } else {
                // Nueva página: el título "DIBUJOS" queda a ~2 cm del top absoluto
                // de la hoja, no sumado al margen superior.
                currentPage = new PDPage(PDRectangle.A4);
                destDoc.addPage(currentPage);
                destY = pageH - sectionTitleTopPad;
                LOG.info("insertFiguresContinuous: primera figura → página nueva, DIBUJOS a 2 cm del top");
            }

            // ── Título "DIBUJOS" (antes de la primera figura) ──
            try (PDPageContentStream cs = new PDPageContentStream(
                    destDoc, currentPage, AppendMode.APPEND, true, true)) {
                String secTitle = "DIBUJOS";
                float secTitleX = marginL + (usableW - estimateTextWidth(secTitle, 12f)) / 2f;
                float secTitleY = destY - 15f;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12f);
                cs.setNonStrokingColor(0, 0, 0);
                cs.newLineAtOffset(secTitleX, secTitleY);
                cs.showText(secTitle);
                cs.endText();
            }
            destY -= sectionTitleH;

            // ── Clamp defensivo: la primera figura debe caber entre destY y el margen inferior ──
            float availFirst = destY - bodyBottom;
            if (sH[0] > availFirst && availFirst > 50f) {
                float fit = availFirst / sH[0];
                sW[0] *= fit;
                sH[0] = availFirst;
                LOG.info("insertFiguresContinuous: primera figura reescalada a "
                       + String.format("%.1fx%.1f", sW[0], sH[0]) + " pt para caber");
            }

            // ── Una figura por página, sin etiqueta individual ──
            // Primera figura: top-aligned debajo del título "DIBUJOS".
            // Figuras 2..N: cada una centrada verticalmente en su propia hoja.
            for (int i = 0; i < figures.size(); i++) {
                float blockTop;
                if (i == 0) {
                    blockTop = destY;
                } else {
                    currentPage = new PDPage(PDRectangle.A4);
                    destDoc.addPage(currentPage);
                    blockTop = bodyBottom + (usableH + sH[i]) / 2f;
                }

                float imgX = marginL + (usableW - sW[i]) / 2f;
                float imgY = blockTop - sH[i];

                try (PDPageContentStream cs = new PDPageContentStream(
                        destDoc, currentPage, AppendMode.APPEND, true, true)) {
                    cs.drawImage(pdfImages.get(i), imgX, imgY, sW[i], sH[i]);
                }

                LOG.info("insertFiguresContinuous: figura " + (i + 1) + " colocada ("
                       + String.format("%.1fx%.1f", sW[i], sH[i]) + " pt)");
            }

            destDoc.save(destPdf);
        }
    }

    /**
     * Extracción simple para Diseños Industriales: cada página del PDF es UNA
     * unidad visual. Renderiza la página, recorta márgenes blancos y la
     * devuelve como FigureUnit. No separa figuras dentro de una página
     * (preserva, por ejemplo, una página con varias vistas de la misma pieza).
     *
     * DPI variable: se ajusta para que la imagen renderizada no exceda
     * {@code TARGET_MAX_PX} en su lado largo, manteniendo un tamaño de
     * archivo razonable incluso para PDFs de gran formato (ej. 875×1237 mm).
     * Las dimensiones físicas en pt se calculan a partir del tamaño real
     * de la página (no del DPI), así el escalado posterior es correcto.
     */
    private List<FigureUnit> extractFigureImagesByPage(PDDocument doc) throws Exception {
        final int TARGET_MAX_PX = 2400;   // ~A4 a 200 DPI
        final float MAX_DPI = 200f;
        List<FigureUnit> figures = new ArrayList<>();
        PDFRenderer renderer = new PDFRenderer(doc);
        int n = doc.getNumberOfPages();
        for (int p = 0; p < n; p++) {
            PDPage page = doc.getPage(p);
            float pageWPt = page.getMediaBox().getWidth();
            float pageHPt = page.getMediaBox().getHeight();
            float longSidePt = Math.max(pageWPt, pageHPt);
            float dpi = Math.min(MAX_DPI, TARGET_MAX_PX * 72f / longSidePt);

            java.awt.image.BufferedImage pageImg =
                    renderer.renderImageWithDPI(p, dpi, ImageType.RGB);
            if (pageImg.getWidth() < 50 || pageImg.getHeight() < 50) continue;

            int fullW = pageImg.getWidth();
            int fullH = pageImg.getHeight();
            java.awt.image.BufferedImage cropped = autoCropWhiteMargins(pageImg, 8);
            if (cropped.getWidth() < 80 || cropped.getHeight() < 60) continue;

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(cropped, "png", baos);
            byte[] data = baos.toByteArray();

            if (data.length > 1024) {
                // Dimensiones físicas: ratio del crop respecto al render completo
                // multiplicado por el tamaño físico real de la página fuente.
                float widthPt  = (float) cropped.getWidth()  / fullW * pageWPt;
                float heightPt = (float) cropped.getHeight() / fullH * pageHPt;
                figures.add(new FigureUnit(data, widthPt, heightPt));
                LOG.info("extractFigureImagesByPage: pág " + (p + 1)
                       + " → unidad (" + cropped.getWidth() + "x" + cropped.getHeight()
                       + " px @ " + String.format("%.0f", dpi) + " dpi → "
                       + String.format("%.1fx%.1f", widthPt, heightPt) + " pt, "
                       + (data.length / 1024) + " KB)");
            }
        }
        LOG.info("extractFigureImagesByPage: total unidades = " + figures.size());
        return figures;
    }

    /**
     * Extrae figuras del PDF de dibujos para patentes industriales.
     *
     * Estrategia de detección por REGIÓN VISUAL COMPUESTA:
     *
     * FASE 1 — Renderizar: cada página se renderiza a 200 DPI.
     *
     * FASE 2 — Detectar bloques: se identifican todos los bloques de contenido
     *          (bandas horizontales con píxeles no-blancos) usando un split fino.
     *
     * FASE 3 — Clasificar: cada bloque se clasifica como GRÁFICO o TEXTO
     *          según presencia de color, filas densas, y patrón de interlineado.
     *
     * FASE 4 — Agrupar: los bloques GRÁFICOS cercanos se fusionan en una
     *          sola FigureRegion. Bloques de texto que estén ENTRE dos bloques
     *          gráficos de la misma composición se incluyen en la región.
     *          Bloques de texto aislados (no entre gráficos) se descartan.
     *
     * FASE 5 — Extraer: cada región agrupada se recorta del render y se
     *          agrega como una figura independiente.
     *
     * NO se voltean, rotan ni invierten imágenes.
     */
    private List<FigureData> extractFigureImages(PDDocument doc) throws Exception {
        List<FigureData> figures = new ArrayList<>();
        PDFRenderer renderer = new PDFRenderer(doc);

        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            LOG.info("extractFigureImages: procesando pág " + (p + 1));

            // ── FASE 1: Renderizar ──
            java.awt.image.BufferedImage pageImg =
                    renderer.renderImageWithDPI(p, 200, ImageType.RGB);

            int pageW = pageImg.getWidth();
            int pageH = pageImg.getHeight();
            if (pageW < 100 || pageH < 100) continue;

            // ── FASE 2: Detectar bloques de contenido ──
            // Split fino (15px) para detectar cada bloque individual
            List<int[]> rawBlocks = detectContentBlocks(pageImg, 15);
            if (rawBlocks.isEmpty()) continue;

            LOG.info("extractFigureImages: pág " + (p + 1)
                   + " → " + rawBlocks.size() + " bloques raw");

            // ── FASE 2.5: Mapa de texto seleccionable desde el PDF original ──
            // Usa PDFBox para saber dónde hay texto REAL (seleccionable)
            // en la página. Esto es infalible: texto bold, títulos, listas,
            // descripciones — todo queda marcado como texto sin importar
            // cuán grueso se vea al renderizar.
            boolean[] selectableTextMap = buildSelectableTextMap(doc, p, pageH);

            // ── FASE 3: Clasificar cada bloque (HÍBRIDO) ──
            // Combina DOS fuentes de información:
            //   A) Texto seleccionable del PDF original (mapa de textMap)
            //   B) Análisis visual de la imagen renderizada
            //
            // Regla híbrida:
            //   - Si tiene texto seleccionable Y TAMBIÉN gráficos reales
            //     (color, líneas densas, bordes) → FIGURA con texto interno
            //   - Si tiene texto seleccionable SIN gráficos reales
            //     → TEXTO externo puro → descartar
            //   - Si NO tiene texto seleccionable → análisis visual normal
            List<ClassifiedBlock> classified = new ArrayList<>();
            for (int[] bounds : rawBlocks) {
                int bH = bounds[1] - bounds[0];
                if (bH < 15) continue;

                // A) Cobertura de texto seleccionable en este bloque
                int textRows = 0;
                for (int y = bounds[0]; y < bounds[1]; y++) {
                    if (y < selectableTextMap.length && selectableTextMap[y]) {
                        textRows++;
                    }
                }
                float textCoverage = (float) textRows / bH;

                java.awt.image.BufferedImage blockImg =
                        pageImg.getSubimage(0, bounds[0], pageW, bH);

                boolean isGraphic;
                if (textCoverage > 0.3f) {
                    // Bloque con texto seleccionable significativo.
                    // Pregunta clave: ¿también tiene contenido GRÁFICO real?
                    // Si sí → es una FIGURA que contiene etiquetas de texto
                    // Si no → es texto externo puro (descripciones, listas)
                    isGraphic = hasGraphicContentBeyondText(blockImg);
                } else {
                    // Poco o nada de texto seleccionable → análisis visual
                    isGraphic = classifyBlockAsGraphic(blockImg);
                }
                classified.add(new ClassifiedBlock(bounds[0], bounds[1], isGraphic));
                LOG.fine("  bloque [" + bounds[0] + "-" + bounds[1]
                       + "] " + bH + "px textCov=" + String.format("%.0f%%", textCoverage*100)
                       + " → " + (isGraphic ? "GRÁFICO" : "TEXTO"));
            }

            // ── FASE 3.5: Fallback — sub-dividir bloques grandes sin texto ──
            // Bloques clasificados como TEXTO que son muy grandes y no tienen
            // texto seleccionable podrían contener figuras con líneas finas que
            // se diluyen al promediar. Se sub-dividen con gap más fino y se
            // reclasifican los sub-bloques.
            for (int ci = 0; ci < classified.size(); ci++) {
                ClassifiedBlock cb = classified.get(ci);
                if (cb.isGraphic) continue;
                int bH = cb.bottom - cb.top;
                if (bH < 400) continue;

                int txtRows = 0;
                for (int y = cb.top; y < cb.bottom; y++) {
                    if (y < selectableTextMap.length && selectableTextMap[y]) txtRows++;
                }
                if ((float) txtRows / bH > 0.1f) continue;

                java.awt.image.BufferedImage bImg =
                        pageImg.getSubimage(0, cb.top, pageW, bH);
                List<int[]> subBlocks = detectContentBlocks(bImg, 30);
                int graphicSub = 0;
                for (int[] sb : subBlocks) {
                    int sbH = sb[1] - sb[0];
                    if (sbH < 30) continue;
                    java.awt.image.BufferedImage subImg =
                            bImg.getSubimage(0, sb[0], pageW, sbH);
                    if (classifyBlockAsGraphic(subImg)) graphicSub++;
                }
                if (graphicSub > 0) {
                    classified.set(ci, new ClassifiedBlock(cb.top, cb.bottom, true));
                    LOG.info("  fallback sub-split: bloque [" + cb.top + "-" + cb.bottom
                           + "] reclasificado GRÁFICO (" + graphicSub + " sub-bloques gráficos)");
                }
            }

            // ── FASE 4: Agrupar bloques gráficos cercanos en figuras ──
            List<int[]> figureRegions = groupIntoFigureRegions(classified, pageH);

            LOG.info("extractFigureImages: pág " + (p + 1)
                   + " → " + figureRegions.size() + " regiones de figura");

            // ── FASE 4.5: Refinar regiones — recortar texto externo en bordes ──
            // Para cada región, verificar si los bordes tienen bloques que
            // son texto seleccionable SIN gráficos (texto externo). Si sí, recortar.
            // Bloques con texto + gráficos en bordes se conservan (parte de la figura).
            for (int r = 0; r < figureRegions.size(); r++) {
                int[] refined = refineRegionWithTextMap(
                        pageImg, figureRegions.get(r), selectableTextMap);
                figureRegions.set(r, refined);
            }

            // ── FASE 5: Extraer cada región como imagen ──
            for (int[] region : figureRegions) {
                int rTop = Math.max(0, region[0] - 5);
                int rBot = Math.min(pageH, region[1] + 5);
                int rH = rBot - rTop;
                if (rH < 80) continue;

                java.awt.image.BufferedImage regionImg =
                        pageImg.getSubimage(0, rTop, pageW, rH);
                regionImg = autoCropWhiteMargins(regionImg, 8);
                if (regionImg.getWidth() < 80 || regionImg.getHeight() < 60) continue;

                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(regionImg, "png", baos);
                byte[] data = baos.toByteArray();

                if (data.length > 1024) {
                    FigureData fd = new FigureData();
                    fd.imageBytes = data;
                    fd.label = null;
                    // Conversión 200 DPI → pt: pt = px * 72 / 200
                    fd.widthPt  = regionImg.getWidth()  * 72f / 200f;
                    fd.heightPt = regionImg.getHeight() * 72f / 200f;
                    figures.add(fd);
                    LOG.info("extractFigureImages: pág " + (p + 1)
                           + " → figura (" + regionImg.getWidth()
                           + "x" + regionImg.getHeight() + " px, "
                           + String.format("%.1fx%.1f", fd.widthPt, fd.heightPt) + " pt)");
                }
            }
        }
        LOG.info("extractFigureImages: total figuras = " + figures.size());
        return figures;
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Detección y agrupación de regiones visuales ─────────────────
    // ══════════════════════════════════════════════════════════════════

    /** Bloque de contenido clasificado como gráfico o texto. */
    private static final class ClassifiedBlock {
        final int top;     // coordenada Y superior (en píxeles)
        final int bottom;  // coordenada Y inferior
        final boolean isGraphic;

        ClassifiedBlock(int top, int bottom, boolean isGraphic) {
            this.top = top;
            this.bottom = bottom;
            this.isGraphic = isGraphic;
        }
    }

    /**
     * Detecta bloques de contenido en una página renderizada.
     * Retorna lista de [yTop, yBottom] para cada bloque separado
     * por al menos minGap píxeles de filas blancas.
     */
    private List<int[]> detectContentBlocks(java.awt.image.BufferedImage img, int minGap) {
        int w = img.getWidth();
        int h = img.getHeight();
        int threshold = 242;
        int step = Math.max(1, w / 250);

        boolean[] whiteRow = new boolean[h];
        for (int y = 0; y < h; y++) {
            int nonWhite = 0;
            int samples = 0;
            for (int x = 0; x < w; x += step) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                samples++;
                if (r < threshold || g < threshold || b < threshold) {
                    nonWhite++;
                }
            }
            whiteRow[y] = (samples > 0 && (float) nonWhite / samples <= 0.02f);
        }

        List<int[]> blocks = new ArrayList<>();
        int contentStart = -1;
        int consecutiveWhite = 0;

        for (int y = 0; y < h; y++) {
            if (whiteRow[y]) {
                consecutiveWhite++;
                if (contentStart >= 0 && consecutiveWhite >= minGap) {
                    blocks.add(new int[]{contentStart, y - consecutiveWhite + 1});
                    contentStart = -1;
                }
            } else {
                consecutiveWhite = 0;
                if (contentStart < 0) contentStart = y;
            }
        }
        if (contentStart >= 0) {
            blocks.add(new int[]{contentStart, h});
        }
        return blocks;
    }

    /**
     * Clasifica un bloque como GRÁFICO (true) o TEXTO PURO (false).
     *
     * Un bloque es GRÁFICO si cumple al menos una condición:
     *   - Tiene filas densas (>15% de relleno no-blanco) → líneas/bordes/fondos
     *   - Tiene píxeles de color (canales RGB difieren >30) → figuras coloreadas
     *   - Tiene poca alternancia texto (pocas transiciones) pero mucho contenido
     *
     * Un bloque es TEXTO PURO si:
     *   - Solo tiene filas delgadas (~texto renderizado a 200 DPI)
     *   - Ninguna fila tiene >15% relleno
     *   - No tiene colores significativos
     *   - Tiene muchas transiciones blanco→contenido (interlineado regular)
     */
    private boolean classifyBlockAsGraphic(java.awt.image.BufferedImage block) {
        int w = block.getWidth();
        int h = block.getHeight();
        int whiteThreshold = 240;
        int step = Math.max(1, w / 200);

        int heavyRows = 0;
        int colorRows = 0;
        int contentRows = 0;
        int transitions = 0;
        boolean prevWhite = true;
        // ── Métricas adicionales de compatibilidad ──
        int ultraDenseRows = 0;   // filas con >40% relleno
        int maxConsecDense = 0;   // banda continua más larga con >8% relleno
        int consecDense = 0;

        for (int y = 0; y < h; y++) {
            int nonWhite = 0;
            int colored = 0;
            int samples = 0;
            for (int x = 0; x < w; x += step) {
                int rgb = block.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                samples++;
                if (r < whiteThreshold || g < whiteThreshold || b < whiteThreshold) {
                    nonWhite++;
                }
                int maxCh = Math.max(r, Math.max(g, b));
                int minCh = Math.min(r, Math.min(g, b));
                if (maxCh - minCh > 30 && maxCh < 240) {
                    colored++;
                }
            }
            boolean isWhite = (samples > 0 && (float) nonWhite / samples <= 0.02f);
            boolean isHeavy = (samples > 0 && (float) nonWhite / samples > 0.15f);
            boolean hasColor = (samples > 0 && (float) colored / samples > 0.02f);
            float fillRatio = samples > 0 ? (float) nonWhite / samples : 0;

            if (!isWhite) {
                contentRows++;
                if (prevWhite) transitions++;
            }
            if (isHeavy) heavyRows++;
            if (hasColor) colorRows++;
            if (fillRatio > 0.40f) ultraDenseRows++;
            if (fillRatio > 0.08f) {
                consecDense++;
                maxConsecDense = Math.max(maxConsecDense, consecDense);
            } else {
                consecDense = 0;
            }
            prevWhite = isWhite;
        }

        // Condiciones que indican contenido gráfico
        float heavyRatio = h > 0 ? (float) heavyRows / h : 0;
        float colorRatio = h > 0 ? (float) colorRows / h : 0;
        float avgLineH = transitions > 0 ? (float) contentRows / transitions : h;

        // Si hay filas densas → gráfico (líneas gruesas, bordes, fondos)
        if (heavyRatio > 0.05f) return true;
        // Si tiene color → gráfico
        if (colorRatio > 0.02f) return true;
        // Diagrama simple: muy alto (>100px), pocas transiciones, contenido grueso
        // Excluye texto suelto como "MEDIDAS" (que es pequeño y poco denso)
        if (transitions <= 2 && avgLineH > 20f && h > 100) return true;

        // ── Validaciones adicionales de compatibilidad ──
        // Banda continua densa >40px: líneas, bordes o rellenos que el texto
        // renderizado a 200 DPI nunca produce (caracteres miden ~30px máx)
        if (maxConsecDense > 40) return true;
        // Filas ultra-densas (>40% relleno): rellenos sólidos o líneas gruesas
        // que el texto (incluso bold) jamás alcanza
        if (ultraDenseRows > 2) return true;
        // Bloque grande con bandas de contenido mucho más altas que texto.
        // A 200 DPI el texto tiene avgLineH ~15-30px; dibujos/diagramas 50+.
        // Guards: h > 400 (bloque sustancial), heavyRatio > 0 (algo de densidad)
        if (avgLineH > 60f && h > 400 && heavyRatio > 0.01f) return true;

        // Todo lo demás es texto puro (títulos, etiquetas externas, descripciones)
        return false;
    }

    /**
     * Agrupa bloques clasificados en regiones de figura.
     *
     * Lógica ESTRICTA — solo bloques GRÁFICOS definen las regiones:
     * 1. Solo bloques GRÁFICOS participan en la construcción de regiones
     * 2. Bloques gráficos cercanos (<180px) se fusionan en una región
     * 3. Bloques de TEXTO NUNCA extienden ni expanden una región
     * 4. Si un bloque de texto cae espacialmente DENTRO de una región
     *    (entre dos gráficos ya fusionados), se incluye automáticamente
     *    porque la región ya abarca esas coordenadas
     * 5. Texto externo (fuera del span gráfico) queda excluido siempre
     *
     * @param blocks   bloques clasificados en orden vertical
     * @param pageH    altura total de la página en px
     * @return lista de [top, bottom] de cada región de figura
     */
    private List<int[]> groupIntoFigureRegions(List<ClassifiedBlock> blocks, int pageH) {
        if (blocks.isEmpty()) return new ArrayList<>();

        // Distancia máxima entre bloques gráficos para considerarlos
        // parte de la misma composición (180px a 200DPI ≈ 23mm)
        int maxGapBetweenGraphics = 180;

        List<int[]> regions = new ArrayList<>();
        int regionTop = -1;
        int regionBottom = -1;

        for (int i = 0; i < blocks.size(); i++) {
            ClassifiedBlock block = blocks.get(i);

            // Solo bloques GRÁFICOS construyen regiones.
            // Bloques de texto se ignoran completamente — no extienden
            // ni expanden la región bajo ninguna circunstancia.
            if (!block.isGraphic) continue;

            if (regionTop < 0) {
                // Iniciar nueva región con este bloque gráfico
                regionTop = block.top;
                regionBottom = block.bottom;
            } else {
                int gap = block.top - regionBottom;
                if (gap <= maxGapBetweenGraphics) {
                    // Extender la región (misma composición de figuras)
                    regionBottom = block.bottom;
                } else {
                    // Demasiado lejos: cerrar región actual, iniciar nueva
                    regions.add(new int[]{regionTop, regionBottom});
                    regionTop = block.top;
                    regionBottom = block.bottom;
                }
            }
        }
        // Cerrar última región si existe
        if (regionTop >= 0) {
            regions.add(new int[]{regionTop, regionBottom});
        }

        LOG.fine("groupIntoFigureRegions: " + regions.size() + " regiones agrupadas");
        return regions;
    }

    /**
     * Construye un mapa booleano por fila de píxel que indica dónde
     * hay TEXTO SELECCIONABLE en el PDF original.
     *
     * Usa PDFBox a nivel de PDF (no de imagen renderizada) para
     * extraer las posiciones de cada carácter de texto real.
     * Esto es infalible: texto bold, títulos, descripciones, listas
     * — todo queda marcado correctamente como texto, sin importar
     * cuán grueso o denso se vea al renderizar.
     *
     * @param doc         documento PDF
     * @param pageIndex   índice de la página (0-based)
     * @param renderHeight  altura de la imagen renderizada en px
     * @return array donde textMap[y]=true si la fila y tiene texto seleccionable
     */
    private boolean[] buildSelectableTextMap(
            PDDocument doc, int pageIndex, int renderHeight) {
        boolean[] textMap = new boolean[renderHeight];
        float scale = 200f / 72f; // DPI / points-per-inch

        try {
            List<float[]> positions = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    // getYDirAdj(): Y desde arriba de la página, ajustado
                    // — coincide con la orientación de la imagen renderizada
                    float yBaseline = text.getYDirAdj();
                    float charH = text.getHeight();
                    positions.add(new float[]{yBaseline, charH});
                }
            };
            stripper.setStartPage(pageIndex + 1); // 1-based
            stripper.setEndPage(pageIndex + 1);
            stripper.getText(doc); // dispara el procesamiento

            // Mapear posiciones PDF a píxeles del render
            for (float[] pos : positions) {
                int pixBaseline = (int)(pos[0] * scale);
                int pixCharH = Math.max(1, (int)(pos[1] * scale));
                int pixTop = Math.max(0, pixBaseline - pixCharH);
                int pixBot = Math.min(renderHeight - 1, pixBaseline + 2);
                for (int y = pixTop; y <= pixBot; y++) {
                    textMap[y] = true;
                }
            }
            LOG.fine("buildSelectableTextMap: pág " + (pageIndex + 1)
                   + " → " + positions.size() + " posiciones de texto");
        } catch (Exception e) {
            LOG.warning("buildSelectableTextMap fallo: " + e.getMessage());
        }
        return textMap;
    }

    /**
     * Refina una región de figura recortando texto externo en los bordes.
     *
     * Lógica HÍBRIDA: para cada sub-bloque en los bordes:
     *   - Si tiene texto seleccionable Y NO tiene gráficos → texto externo → recortar
     *   - Si tiene texto seleccionable Y SÍ tiene gráficos → parte de la figura → detener
     *   - Si NO tiene texto seleccionable → contenido gráfico → detener
     */
    private int[] refineRegionWithTextMap(
            java.awt.image.BufferedImage pageImg, int[] region, boolean[] textMap) {
        int pageW = pageImg.getWidth();
        int rTop = region[0];
        int rBot = region[1];
        int rH = rBot - rTop;
        if (rH < 100) return region;

        java.awt.image.BufferedImage regionImg =
                pageImg.getSubimage(0, rTop, pageW, rH);
        List<int[]> blocks = detectContentBlocks(regionImg, 8);
        if (blocks.size() <= 1) return region;

        // Recortar desde abajo
        int coreBottom = rH;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            int bTop = blocks.get(i)[0];
            int bBot = blocks.get(i)[1];
            int bH = bBot - bTop;
            if (bH > 200) break;

            int textRows = 0;
            for (int y = rTop + bTop; y < rTop + bBot && y < textMap.length; y++) {
                if (textMap[y]) textRows++;
            }
            float textCov = (float) textRows / bH;

            if (textCov > 0.3f) {
                // Tiene texto seleccionable. ¿También tiene gráficos?
                java.awt.image.BufferedImage bImg =
                        regionImg.getSubimage(0, bTop, pageW, bH);
                if (!hasGraphicContentBeyondText(bImg)) {
                    // Texto puro sin gráficos → recortar
                    coreBottom = bTop;
                    LOG.info("refineRegion: recorte inferior ["
                           + (rTop + bTop) + "-" + (rTop + bBot)
                           + "] textCov=" + String.format("%.0f%%", textCov*100)
                           + " sin gráficos");
                } else {
                    break; // Tiene texto + gráficos → parte de la figura
                }
            } else {
                break; // Contenido gráfico sin texto → parte de la figura
            }
        }

        // Recortar desde arriba
        int coreTop = 0;
        for (int i = 0; i < blocks.size(); i++) {
            int bTop = blocks.get(i)[0];
            int bBot = blocks.get(i)[1];
            int bH = bBot - bTop;
            if (bH > 200) break;

            int textRows = 0;
            for (int y = rTop + bTop; y < rTop + bBot && y < textMap.length; y++) {
                if (textMap[y]) textRows++;
            }
            float textCov = (float) textRows / bH;

            if (textCov > 0.3f) {
                java.awt.image.BufferedImage bImg =
                        regionImg.getSubimage(0, bTop, pageW, bH);
                if (!hasGraphicContentBeyondText(bImg)) {
                    coreTop = bBot;
                    LOG.info("refineRegion: recorte superior ["
                           + (rTop + bTop) + "-" + (rTop + bBot)
                           + "] textCov=" + String.format("%.0f%%", textCov*100)
                           + " sin gráficos");
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        if (coreTop >= coreBottom) return region;
        return new int[]{rTop + coreTop, rTop + coreBottom};
    }

    /**
     * Determina si un bloque tiene contenido GRÁFICO REAL más allá de texto.
     *
     * Busca indicadores que el TEXTO PURO jamás produce:
     *
     * 1. COLOR: texto es típicamente negro/gris. Figuras tienen elementos
     *    coloreados (verde, azul, rojo, etc.).
     *
     * 2. FILAS ULTRA-DENSAS (>40% relleno): una fila de texto bold a 200 DPI
     *    tiene ~20-25% de relleno como máximo. Líneas horizontales, bordes
     *    y fondos llegan a >40%.
     *
     * 3. BANDAS DENSAS CONTINUAS LARGAS (>40px): los caracteres de texto
     *    a 200 DPI miden ~30-35px de alto. Una banda continua de filas
     *    densas (>8% relleno) mayor a 40px indica líneas, bordes, frames.
     *
     * Si CUALQUIERA de estos se cumple → el bloque tiene gráficos reales.
     * Si NINGUNO se cumple → es texto puro sin gráficos.
     *
     * Esto permite que una FIGURA con texto seleccionable dentro se conserve
     * (porque la figura tiene color o líneas) mientras que texto externo
     * (MEDIDAS, ELEMENTOS, descripciones) se descarte.
     */
    private boolean hasGraphicContentBeyondText(java.awt.image.BufferedImage block) {
        int w = block.getWidth();
        int h = block.getHeight();
        int step = Math.max(1, w / 200);
        int whiteThreshold = 240;

        int ultraDenseRows = 0;   // filas con >40% relleno
        int colorRows = 0;        // filas con píxeles de color
        int maxConsecDense = 0;   // banda continua más larga de filas >8% relleno
        int consecDense = 0;

        for (int y = 0; y < h; y++) {
            int nonWhite = 0;
            int colored = 0;
            int samples = 0;
            for (int x = 0; x < w; x += step) {
                int rgb = block.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                samples++;
                if (r < whiteThreshold || g < whiteThreshold || b < whiteThreshold) {
                    nonWhite++;
                }
                int maxCh = Math.max(r, Math.max(g, b));
                int minCh = Math.min(r, Math.min(g, b));
                if (maxCh - minCh > 30 && maxCh < 240) {
                    colored++;
                }
            }
            float fillRatio = samples > 0 ? (float) nonWhite / samples : 0;
            boolean hasColor = samples > 0 && (float) colored / samples > 0.01f;

            if (fillRatio > 0.40f) ultraDenseRows++;
            if (hasColor) colorRows++;
            if (fillRatio > 0.08f) {
                consecDense++;
                maxConsecDense = Math.max(maxConsecDense, consecDense);
            } else {
                consecDense = 0;
            }
        }

        // Indicador 1: color (texto puro NO tiene color)
        if (colorRows > 3) return true;
        // Indicador 2: filas ultra-densas (texto nunca llega a >40%)
        if (ultraDenseRows > 2) return true;
        // Indicador 3: banda densa continua larga (>40px → borde/frame/línea)
        if (maxConsecDense > 40) return true;

        return false;
    }

    /**
     * Recorta los márgenes blancos de una imagen renderizada.
     * Escanea desde los bordes hacia el centro para encontrar el
     * contenido no-blanco y recorta con un padding configurable.
     */
    private java.awt.image.BufferedImage autoCropWhiteMargins(
            java.awt.image.BufferedImage img, int padding) {
        int w = img.getWidth();
        int h = img.getHeight();
        int whiteThreshold = 245;

        int top = 0, bottom = h - 1, left = 0, right = w - 1;

        // Encontrar borde superior
        topScan:
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x += 2) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < whiteThreshold || g < whiteThreshold || b < whiteThreshold) {
                    top = y;
                    break topScan;
                }
            }
        }

        // Encontrar borde inferior
        bottomScan:
        for (int y = h - 1; y > top; y--) {
            for (int x = 0; x < w; x += 2) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < whiteThreshold || g < whiteThreshold || b < whiteThreshold) {
                    bottom = y;
                    break bottomScan;
                }
            }
        }

        // Encontrar borde izquierdo
        leftScan:
        for (int x = 0; x < w; x++) {
            for (int y = top; y <= bottom; y += 2) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < whiteThreshold || g < whiteThreshold || b < whiteThreshold) {
                    left = x;
                    break leftScan;
                }
            }
        }

        // Encontrar borde derecho
        rightScan:
        for (int x = w - 1; x > left; x--) {
            for (int y = top; y <= bottom; y += 2) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < whiteThreshold || g < whiteThreshold || b < whiteThreshold) {
                    right = x;
                    break rightScan;
                }
            }
        }

        // Aplicar padding
        top = Math.max(0, top - padding);
        bottom = Math.min(h - 1, bottom + padding);
        left = Math.max(0, left - padding);
        right = Math.min(w - 1, right + padding);

        int cropW = right - left + 1;
        int cropH = bottom - top + 1;

        if (cropW < 50 || cropH < 50) return img; // Sin contenido significativo

        return img.getSubimage(left, top, cropW, cropH);
    }

    /** Encuentra la Y más baja con contenido en la página actual. */
    private float findLowestContentY(PDDocument doc, PDPage page, float defaultBottom) {
        try {
            float pageH = page.getMediaBox().getHeight();
            ContentStreamFragmenter.FragmentResult scan =
                    ContentStreamFragmenter.extractByYRange(page, 0f, pageH);
            if (scan.hasContent() && scan.minContentY > 0) {
                return Math.max(scan.minContentY - 10f, defaultBottom);
            }
        } catch (Exception e) {
            LOG.fine("findLowestContentY: " + e.getMessage());
        }
        return defaultBottom;
    }

    /** Estimación simple del ancho de texto en Helvetica-Bold. */
    private float estimateTextWidth(String text, float fontSize) {
        return text.length() * fontSize * 0.55f;
    }

    /** Elimina caracteres no imprimibles de texto para PDFBox. */
    private String sanitizeText(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c <= 0xFF) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static class FigureData {
        byte[] imageBytes;
        String label;
        float widthPt;   // dimensión física original (renderizado a 200 DPI → pt)
        float heightPt;
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Helpers ──────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════

    /**
     * Busca la imagen del separador SENADI en rutas conocidas.
     */
    private byte[] findSeparatorImageBytes() {
        // 1) Classpath (dentro del WAR): funciona en producción
        try (java.io.InputStream is = getClass().getClassLoader()
                .getResourceAsStream("images/separador.png")) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            LOG.warning("findSeparatorImageBytes: error classpath: " + e.getMessage());
        }
        // 2) Fallback: ruta JBoss/WildFly deployments
        String jbossBase = System.getProperty("jboss.server.base.dir", "");
        if (!jbossBase.isEmpty()) {
            File f = new File(jbossBase + File.separator + "deployments"
                    + File.separator + "separador.png");
            if (f.exists() && f.isFile()) {
                try {
                    return java.nio.file.Files.readAllBytes(f.toPath());
                } catch (Exception e) {
                    LOG.warning("findSeparatorImageBytes: error leyendo archivo: " + e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * Genera solo la página de datos (sin reivindicaciones).
     */
    public File generateDataPageOnly(PatentData data, File tempDir) throws Exception {
        ensureDir(tempDir);
        File outputPdf = new File(tempDir, "RESULTADO_DATOS.pdf");
        patentPdfGen.generate(data, outputPdf);
        return outputPdf;
    }

    /**
     * Procesa Patentes de Invención / Modelos de Utilidad.
     * Usa el mismo flujo de inserción por content stream que processPatentFromDB:
     * detecta la región de la 1ª reivindicación, la inserta en continuidad
     * después del formulario usando insertClaimContentStream.
     */
    public File processPatent(File formularioPdf, File reivindicacionesPdf,
                              File tempDir, boolean ocultarContacto) throws Exception {
        ensureDir(tempDir);

        File tempForm = new File(tempDir, "_temp_form.pdf");

        try {
            File formToUse = formularioPdf;
            if (ocultarContacto) {
                formProcessor.ocultarTelefonoYFax(formularioPdf, tempForm);
                formToUse = tempForm;
            }

            // Copiar formulario como base del resultado
            File outputPdf = new File(tempDir, "RESULTADO_FINAL.pdf");
            java.nio.file.Files.copy(formToUse.toPath(), outputPdf.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Calcular espacio restante en la última página del formulario
            float remainingY = calcRemainingY(outputPdf);
            LOG.info("processPatent: remainingY del formulario = " + remainingY + "pt");

            // Insertar reivindicaciones con el mismo flujo que processPatentFromDB
            if (reivindicacionesPdf != null && reivindicacionesPdf.exists()) {
                try {
                    ClaimsFirstLiteralExtractor.ClaimRegion region =
                            claimsExtractor.getFirstClaimRegion(reivindicacionesPdf);
                    if (region != null) {
                        LOG.info("processPatent: Claim1 pag=" + region.firstClaimPage
                                + " Y=" + region.firstClaimYDirAdj
                                + ", Header Y=" + region.headerYDirAdj);
                        insertClaimContentStream(outputPdf, reivindicacionesPdf, region, remainingY);
                    } else {
                        LOG.warning("processPatent: no se detectó región de reivindicaciones");
                    }
                } catch (Exception e) {
                    LOG.warning("processPatent: error insertando reivindicación: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            return outputPdf;
        } finally {
            if (tempForm.exists()) tempForm.delete();
        }
    }

    /**
     * Calcula el espacio vertical restante (en pt) en la última página de un PDF.
     * Escanea el content stream para encontrar el contenido más bajo (minY).
     */
    private float calcRemainingY(File pdfFile) throws Exception {
        float destBodyBottom = 60f;
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            PDPage lastPage = doc.getPage(doc.getNumberOfPages() - 1);
            float srcH = lastPage.getMediaBox().getHeight();
            ContentStreamFragmenter.FragmentResult scan =
                    ContentStreamFragmenter.extractByYRange(lastPage, 0f, srcH);
            if (scan.hasContent() && scan.minContentY > destBodyBottom) {
                return scan.minContentY - destBodyBottom;
            }
            return 0f;
        }
    }

    /**
     * Procesa Diseños Industriales.
     * Usa el mismo flujo de inserción continua que processPatentFromDB.
     */
    public File processDesign(File formularioPdf, File figurasPdf,
                              File tempDir, boolean ocultarContacto) throws Exception {
        ensureDir(tempDir);

        File tempForm = new File(tempDir, "_temp_form.pdf");

        try {
            File formToUse = formularioPdf;
            if (ocultarContacto) {
                formProcessor.ocultarTelefonoYFax(formularioPdf, tempForm);
                formToUse = tempForm;
            }

            File outputPdf = new File(tempDir, "RESULTADO_FINAL.pdf");
            java.nio.file.Files.copy(formToUse.toPath(), outputPdf.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            if (figurasPdf != null && figurasPdf.exists()) {
                try {
                    float remainingY = calcRemainingY(outputPdf);
                    LOG.info("processDesign: remainingY=" + remainingY + "pt");
                    insertFiguresContinuous(outputPdf, figurasPdf, remainingY);
                } catch (Exception e) {
                    LOG.warning("processDesign: error insertando figuras: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            return outputPdf;
        } finally {
            if (tempForm.exists()) tempForm.delete();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── GENERACIÓN POR LOTE (composición continua) ──────────────────
    // ══════════════════════════════════════════════════════════════════

    /**
     * Genera un único PDF con múltiples patentes en composición continua.
     *
     * Cada patente se compone directamente en flujo:
     *   - Bandera/banner SENADI (misma imagen que el flujo individual)
     *   - Datos de la patente escritos línea a línea (fluyen entre páginas)
     *   - Primera reivindicación (content stream fragmentado del PDF fuente)
     *   - Dibujos (content stream fragmentado del PDF fuente)
     *
     * Si después de una patente queda espacio libre, la línea separadora
     * y la siguiente patente comienzan ahí mismo.
     */
    public File processPatentBatchFromDB(
            List<PatentData> dataList,
            List<File> reivFiles,
            List<File> dibFiles,
            File tempDir) throws Exception {
        return processPatentBatchFromDB(dataList, reivFiles, dibFiles, null, tempDir);
    }

    /**
     * Portadas (PDFs) que se insertan al inicio de cada sección del lote.
     * Cualquiera puede ser null si no se desea portada para esa sección.
     */
    public static class SectionCovers {
        public File invencion;   // Patentes de Invención y PCT
        public File modelos;     // Modelos de Utilidad
        public File disenios;    // Diseños Industriales
    }

    /**
     * Igual que {@link #processPatentBatchFromDB(List, List, List, File)} pero
     * inserta una portada de sección antes del primer trámite de cada grupo.
     * Asume que {@code dataList} ya viene ordenada por sección.
     */
    public File processPatentBatchFromDB(
            List<PatentData> dataList,
            List<File> reivFiles,
            List<File> dibFiles,
            SectionCovers covers,
            File tempDir) throws Exception {

        ensureDir(tempDir);

        if (dataList.isEmpty()) {
            throw new IllegalArgumentException("La lista de patentes está vacía.");
        }

        // ── Estrategia: generar cada patente con processPatentFromDB
        // (mismo flujo que "Generar PDF Individual") y luego fusionar los
        // resultados con PDFMergerUtility. Esto garantiza paridad funcional
        // entre individual y lote sin duplicar la lógica de claims/dibujos.
        // Como cada PDF individual termina en su propia hoja, al fusionarse
        // cada patente del lote arranca automáticamente en una hoja nueva.
        List<File> individualPdfs = new ArrayList<>();
        String seccionAnterior = null;
        for (int i = 0; i < dataList.size(); i++) {
            PatentData pd = dataList.get(i);
            File reivFile = (reivFiles != null && i < reivFiles.size()) ? reivFiles.get(i) : null;
            File dibFile  = (dibFiles  != null && i < dibFiles.size())  ? dibFiles.get(i)  : null;

            // ── Portada de sección al cambiar de grupo ──
            String seccion = seccionDe(pd);
            if (!seccion.equals(seccionAnterior)) {
                File portada = portadaDeSeccion(covers, seccion);
                if (portada != null && portada.exists()) {
                    individualPdfs.add(portada);
                }
                seccionAnterior = seccion;
            }

            File patentDir = new File(tempDir, "patent_" + (i + 1));
            ensureDir(patentDir);
            try {
                File individual = processPatentFromDB(pd, reivFile, dibFile, patentDir);
                // Renombrar para que no colisione al iterar
                File renamed = new File(patentDir, "INDIVIDUAL_" + (i + 1) + ".pdf");
                java.nio.file.Files.move(individual.toPath(), renamed.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                individualPdfs.add(renamed);
                LOG.info("processPatentBatchFromDB: " + pd.getApplicationNumber()
                       + " → " + renamed.length() + " bytes");
            } catch (Exception e) {
                LOG.warning("processPatentBatchFromDB: error generando PDF individual de "
                          + pd.getApplicationNumber() + ": " + e.getMessage());
            }
        }

        File outputPdf = new File(tempDir, "LOTE_FINAL.pdf");
        if (individualPdfs.isEmpty()) {
            throw new IllegalStateException("No se pudo generar ningún PDF individual del lote.");
        }
        merge(outputPdf, individualPdfs.toArray(new File[0]));

        LOG.info("processPatentBatchFromDB: lote fusionado → "
               + outputPdf.length() + " bytes, " + individualPdfs.size() + "/" + dataList.size() + " patentes");
        return outputPdf;
    }

    /**
     * Sección de lote a la que pertenece una patente:
     *   "INVENCION" → Patentes de Invención y PCT
     *   "MODELOS"   → Modelos de Utilidad
     *   "DISENIOS"  → Diseños Industriales
     */
    private String seccionDe(PatentData pd) {
        if (pd.isDisenoIndustrial()) {
            return "DISENIOS";
        }
        if (pd.isModeloUtilidad()) {
            return "MODELOS";
        }
        return "INVENCION"; // PI, PCT y desconocidos
    }

    /** Devuelve la portada configurada para una sección (puede ser null). */
    private File portadaDeSeccion(SectionCovers covers, String seccion) {
        if (covers == null) {
            return null;
        }
        switch (seccion) {
            case "DISENIOS": return covers.disenios;
            case "MODELOS":  return covers.modelos;
            default:         return covers.invencion;
        }
    }

    /**
     * Genera un lote continuo de Diseños Industriales.
     * Extrae figuras de cada PDF de dibujos y las compone con datos + etiquetas.
     */
    public File processDesignBatchFromDB(
            List<PatentData> dataList,
            List<File> dibFiles,
            File tempDir) throws Exception {

        ensureDir(tempDir);

        if (dataList.isEmpty()) {
            throw new IllegalArgumentException("La lista de diseños está vacía.");
        }

        // ── Estrategia: generar cada diseño con processPatentFromDB
        // (que internamente detecta DI y usa insertFiguresContinuous) y luego
        // fusionar con PDFMergerUtility. Mismo enfoque que processPatentBatchFromDB.
        List<File> individualPdfs = new ArrayList<>();
        for (int i = 0; i < dataList.size(); i++) {
            PatentData pd = dataList.get(i);
            File dibFile = (dibFiles != null && i < dibFiles.size()) ? dibFiles.get(i) : null;

            File patentDir = new File(tempDir, "design_" + (i + 1));
            ensureDir(patentDir);
            try {
                File individual = processPatentFromDB(pd, null, dibFile, patentDir);
                File renamed = new File(patentDir, "INDIVIDUAL_" + (i + 1) + ".pdf");
                java.nio.file.Files.move(individual.toPath(), renamed.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                individualPdfs.add(renamed);
                LOG.info("processDesignBatchFromDB: diseño " + (i + 1)
                       + " → " + renamed.length() + " bytes");
            } catch (Exception e) {
                LOG.warning("processDesignBatchFromDB: error generando PDF individual del diseño "
                          + (i + 1) + ": " + e.getMessage());
            }
        }

        File outputPdf = new File(tempDir, "LOTE_DISENOS_FINAL.pdf");
        if (individualPdfs.isEmpty()) {
            throw new IllegalStateException("No se pudo generar ningún PDF individual del lote de diseños.");
        }
        merge(outputPdf, individualPdfs.toArray(new File[0]));

        LOG.info("processDesignBatchFromDB: lote fusionado → "
               + outputPdf.length() + " bytes, " + individualPdfs.size() + "/" + dataList.size() + " diseños");
        return outputPdf;
    }

    /**
     * Genera un lote de "Datos con hojas en blanco".
     * Cada patente incluye: banner + datos + resto vacío + página en blanco.
     * Sin reivindicaciones, sin dibujos, sin persistencia.
     */
    public File processBlankSheetBatch(List<PatentData> dataList, File tempDir)
            throws Exception {
        ensureDir(tempDir);
        if (dataList.isEmpty()) {
            throw new IllegalArgumentException("La lista de patentes está vacía.");
        }

        File outputPdf = new File(tempDir, "LOTE_DATOS_BLANCO.pdf");

        BatchPatentPdfComposer composer = new BatchPatentPdfComposer();
        byte[] bannerImg = findSeparatorImageBytes();
        if (bannerImg != null) {
            composer.setSeparatorImageBytes(bannerImg);
        }
        composer.composeBlankSheetBatch(dataList, outputPdf, tempDir);

        LOG.info("processBlankSheetBatch: lote generado → "
               + outputPdf.length() + " bytes, " + dataList.size() + " patentes");
        return outputPdf;
    }

    private void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("No se pudo crear la carpeta temporal.");
        }
    }


    private void merge(File output, File... sources) throws Exception {
        PDFMergerUtility merger = new PDFMergerUtility();
        for (File src : sources) merger.addSource(src);
        merger.setDestinationFileName(output.getAbsolutePath());
        merger.mergeDocuments(null);
    }
}
