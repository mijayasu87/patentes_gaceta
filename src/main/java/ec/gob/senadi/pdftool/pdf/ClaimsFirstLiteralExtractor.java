package ec.gob.senadi.pdftool.pdf;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Extrae la primera reivindicación de un PDF de claims.
 * Ofrece dos modos:
 *   - generateFirstClaimPdf(): PDF recortado para carga manual (Tab 2)
 *   - extractFirstClaim(): texto + info de fuente para renderizado con OpenPDF (Tab 1)
 */
public class ClaimsFirstLiteralExtractor {

    private static final Pattern FIRST_CLAIM_DETECTOR =
            Pattern.compile("(?m)^\\s*1[ªº]?\\s*[\\.\\-\\)\\:]+\\s*");

    private static final Pattern FIRST_CLAIM_LINE =
            Pattern.compile("^(\\d{1,3}\\s+)?1[ªº]?\\s*[\\.\\-\\)\\:]+");

    private static final Pattern GENERIC_SECOND_CLAIM =
            Pattern.compile("(?m)^\\s*2[ªº]?\\s*[\\.\\-\\)\\:]*\\s+");

    private static final Pattern GENERIC_SECOND_CLAIM_LINE =
            Pattern.compile("^(\\d{1,3}\\s+)?2[ªº]?\\s*[\\.\\-\\)\\:]*\\s+");

    // Formato "Reivindicación N" — con/sin corchetes, con/sin tilde, case-insensitive
    private static final Pattern REIV_FIRST_CLAIM =
            Pattern.compile("(?mi)^\\s*\\[?\\s*reivindicaci[oó]n\\s+1\\s*[.:\\-]?\\s*\\]?");

    private static final Pattern REIV_FIRST_CLAIM_LINE =
            Pattern.compile("(?i)^(\\d{1,3}\\s+)?\\[?\\s*reivindicaci[oó]n\\s+1\\s*[.:\\-]?\\s*\\]?");

    private static final Pattern REIV_SECOND_CLAIM =
            Pattern.compile("(?mi)^\\s*\\[?\\s*reivindicaci[oó]n\\s+2\\s*[.:\\-]?\\s*\\]?");

    private static final Pattern REIV_SECOND_CLAIM_LINE =
            Pattern.compile("(?i)^(\\d{1,3}\\s+)?\\[?\\s*reivindicaci[oó]n\\s+2\\s*[.:\\-]?\\s*\\]?");

    /** Título de sección "REIVINDICACIONES" / "Reivindicaciones" */
    private static final Pattern REIV_HEADER_PATTERN =
            Pattern.compile("(?i)^(\\d{1,3}\\s+)?reivindicaciones\\s*$");
    /** Patrón más flexible: "reivindicaciones" en cualquier parte de la línea */
    private static final Pattern REIV_HEADER_LOOSE =
            Pattern.compile("(?i)(\\d{1,3}\\s+)?reivindicaciones");

    // ── Compatibilidad: formato "Rn:" / "Rn." (convención ecuatoriana R1, R2, …) ──
    private static final Pattern R_CLAIM_FIRST_DETECTOR =
            Pattern.compile("(?mi)^\\s*R\\s*1\\s*[:.\\-]\\s*");
    private static final Pattern R_CLAIM_FIRST_LINE =
            Pattern.compile("(?i)^(\\d{1,3}\\s+)?R\\s*1\\s*[:.\\-]");
    private static final Pattern R_CLAIM_SECOND_DETECTOR =
            Pattern.compile("(?mi)^\\s*R\\s*2\\s*[:.\\-]\\s*");
    private static final Pattern R_CLAIM_SECOND_LINE =
            Pattern.compile("(?i)^(\\d{1,3}\\s+)?R\\s*2\\s*[:.\\-]");

    // ── Compatibilidad: header alternativo "SE REIVINDICA:" ──
    private static final Pattern SE_REIVINDICA_HEADER =
            Pattern.compile("(?i)^(\\d{1,3}\\s+)?se\\s+reivindica\\s*[:.]*\\s*$");

    // ── Compatibilidad: genérico 2ª reiv sin paréntesis (evita confusión con sub-ítems n)) ──
    private static final Pattern GENERIC_SECOND_CLAIM_NO_PAREN =
            Pattern.compile("(?m)^\\s*2[ªº]?\\s*[\\.\\-\\:]+\\s+");
    private static final Pattern GENERIC_SECOND_CLAIM_LINE_NO_PAREN =
            Pattern.compile("^(\\d{1,3}\\s+)?2[ªº]?\\s*[\\.\\-\\:]+\\s+");

    /** Región de la primera reivindicación: coordenadas Y + páginas */
    public static class ClaimRegion {
        public final int firstClaimPage;
        public final float firstClaimYDirAdj;
        public final int secondClaimPage; // -1 si no hay
        public final float secondClaimYDirAdj; // -1 si no hay
        public final int totalPagesWithClaim; // Páginas que contienen parte de la 1ª reiv
        /** Y DirAdj del título "REIVINDICACIONES" (o similar), -1 si no encontrado */
        public final float headerYDirAdj;

        public ClaimRegion(int firstClaimPage, float firstClaimYDirAdj,
                           int secondClaimPage, float secondClaimYDirAdj,
                           int totalPagesWithClaim) {
            this(firstClaimPage, firstClaimYDirAdj, secondClaimPage, secondClaimYDirAdj,
                 totalPagesWithClaim, -1f);
        }

        public ClaimRegion(int firstClaimPage, float firstClaimYDirAdj,
                           int secondClaimPage, float secondClaimYDirAdj,
                           int totalPagesWithClaim, float headerYDirAdj) {
            this.firstClaimPage = firstClaimPage;
            this.firstClaimYDirAdj = firstClaimYDirAdj;
            this.secondClaimPage = secondClaimPage;
            this.secondClaimYDirAdj = secondClaimYDirAdj;
            this.totalPagesWithClaim = totalPagesWithClaim;
            this.headerYDirAdj = headerYDirAdj;
        }
    }

    /**
     * Localiza la primera reivindicación en el PDF (solo lectura, no modifica nada).
     * Devuelve las coordenadas Y (DirAdj) y los números de página.
     */
    public ClaimRegion getFirstClaimRegion(File inputClaimsPdf) throws IOException {
        byte[] data = java.nio.file.Files.readAllBytes(inputClaimsPdf.toPath());
        try (PDDocument doc = PDDocument.load(data)) {
            int totalPages = doc.getNumberOfPages();
            if (totalPages == 0) return null;

            String claimFormat = detectClaimFormat(doc, totalPages);

            // Localizar 1ª reivindicación (formato numérico: "1.", "1-", etc.)
            int firstClaimPage = findPageWithPattern(doc, totalPages, FIRST_CLAIM_DETECTOR);
            boolean reivFormat = false;
            if (firstClaimPage < 0) {
                // Fallback: formato "Reivindicación 1" / "[Reivindicación 1]"
                firstClaimPage = findPageWithPattern(doc, totalPages, REIV_FIRST_CLAIM);
                reivFormat = firstClaimPage >= 0;
            }
            // Compatibilidad: formato "R1:" / "R1." (convención Rn:)
            boolean rClaimFormat = false;
            if (firstClaimPage < 0) {
                firstClaimPage = findPageWithPattern(doc, totalPages, R_CLAIM_FIRST_DETECTOR);
                rClaimFormat = firstClaimPage >= 0;
            }
            if (firstClaimPage < 0) return null;

            float firstClaimYDirAdj;
            if (rClaimFormat) {
                firstClaimYDirAdj = findYPosition(doc, firstClaimPage, R_CLAIM_FIRST_LINE);
            } else if (reivFormat) {
                firstClaimYDirAdj = findYPosition(doc, firstClaimPage, REIV_FIRST_CLAIM_LINE);
            } else {
                firstClaimYDirAdj = findYPosition(doc, firstClaimPage, FIRST_CLAIM_LINE);
                if (firstClaimYDirAdj < 0) {
                    firstClaimYDirAdj = findYPosition(doc, firstClaimPage,
                            Pattern.compile("^\\s*1[ªº]?\\s*" + Pattern.quote(claimFormat)));
                }
                if (firstClaimYDirAdj < 0) {
                    firstClaimYDirAdj = findYPosition(doc, firstClaimPage, REIV_FIRST_CLAIM_LINE);
                    reivFormat = true;
                }
            }
            if (firstClaimYDirAdj < 0) return null;

            // Localizar 2ª reivindicación
            int secondClaimPage;
            if (rClaimFormat) {
                // Convención Rn: buscar R2:
                secondClaimPage = findPageWithPattern(doc, totalPages, R_CLAIM_SECOND_DETECTOR);
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithPattern(doc, totalPages, GENERIC_SECOND_CLAIM_NO_PAREN);
                }
            } else if (reivFormat) {
                secondClaimPage = findPageWithPattern(doc, totalPages, REIV_SECOND_CLAIM);
                // Compatibilidad: formato numérico si "Reivindicación 2" no existe
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithPattern(doc, totalPages, GENERIC_SECOND_CLAIM_NO_PAREN);
                }
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithGenericSecondClaim(doc, totalPages, claimFormat);
                }
            } else {
                Pattern secondPattern = buildSecondClaimPattern(claimFormat);
                secondClaimPage = findPageWithPattern(doc, totalPages, secondPattern);
                // Compatibilidad: genérico sin paréntesis para evitar sub-ítems n)
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithPattern(doc, totalPages, GENERIC_SECOND_CLAIM_NO_PAREN);
                }
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithGenericSecondClaim(doc, totalPages, claimFormat);
                }
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithPattern(doc, totalPages, REIV_SECOND_CLAIM);
                }
            }

            float secondClaimYDirAdj = -1;
            if (secondClaimPage >= 0) {
                if (rClaimFormat) {
                    secondClaimYDirAdj = findYPosition(doc, secondClaimPage, R_CLAIM_SECOND_LINE);
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE_NO_PAREN);
                    }
                } else if (reivFormat) {
                    secondClaimYDirAdj = findYPosition(doc, secondClaimPage, REIV_SECOND_CLAIM_LINE);
                    // Compatibilidad: Y de formato numérico si "Reivindicación 2" no tiene Y
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE_NO_PAREN);
                    }
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE);
                    }
                } else {
                    Pattern lineP = buildSecondClaimLinePattern(claimFormat);
                    secondClaimYDirAdj = findYPosition(doc, secondClaimPage, lineP);
                    // Compatibilidad: genérico sin paréntesis antes del genérico completo
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE_NO_PAREN);
                    }
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE);
                    }
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, REIV_SECOND_CLAIM_LINE);
                    }
                }
            }

            // Calcular cuántas páginas abarca la 1ª reivindicación
            int lastPage = (secondClaimPage >= 0) ? secondClaimPage : totalPages - 1;
            int claimPageCount = lastPage - firstClaimPage + 1;

            // Buscar título "REIVINDICACIONES" en la misma página del claim 1
            float headerYDirAdj = findYPosition(doc, firstClaimPage, REIV_HEADER_PATTERN);
            // Si no se encontró con patrón estricto, intentar patrón flexible
            if (headerYDirAdj < 0) {
                headerYDirAdj = findYPosition(doc, firstClaimPage, REIV_HEADER_LOOSE);
            }
            // Compatibilidad: header "SE REIVINDICA:"
            if (headerYDirAdj < 0) {
                headerYDirAdj = findYPosition(doc, firstClaimPage, SE_REIVINDICA_HEADER);
            }
            // Si aún no se encontró, buscar en la página anterior (podría estar ahí)
            if (headerYDirAdj < 0 && firstClaimPage > 0) {
                float prevPageHeader = findYPosition(doc, firstClaimPage - 1, REIV_HEADER_PATTERN);
                if (prevPageHeader < 0) {
                    prevPageHeader = findYPosition(doc, firstClaimPage - 1, REIV_HEADER_LOOSE);
                }
                // Header en página anterior: no podemos usar su Y directamente
                // porque pertenece a otra página, pero sabemos que las
                // reivindicaciones empiezan al inicio de firstClaimPage.
                // headerYDirAdj se deja en -1 y el fallback cubrirá.
            }

            return new ClaimRegion(firstClaimPage, firstClaimYDirAdj,
                    secondClaimPage, secondClaimYDirAdj, claimPageCount, headerYDirAdj);
        }
    }

    /**
     * Limpia un PDF de reivindicaciones para importación visual:
     * elimina números de página (único cambio al content stream).
     * El archivo se modifica in-place.
     */
    public void cleanForImport(File pdfFile) throws IOException {
        byte[] data = java.nio.file.Files.readAllBytes(pdfFile.toPath());
        try (PDDocument doc = PDDocument.load(data)) {
            stripPageNumbers(doc);
            doc.save(pdfFile);
        }
    }

    /**
     * Pre-procesa un PDF de reivindicaciones para inserción via content stream:
     *   1. Elimina números de página/línea de todas las páginas
     *   2. Elimina contenido encima de la 1ª reivindicación (encabezados, títulos,
     *      «REIVINDICACIONES», preámbulos como «Se reivindica lo siguiente:»)
     *
     * NO modifica el archivo original — escribe el resultado en outputPdf.
     * Las coordenadas Y del ClaimRegion no se invalidan (solo se blanquea texto,
     * no se altera la geometría de la página).
     */
    public void prepareForContentStreamInsert(File inputPdf, ClaimRegion region,
                                               File outputPdf) throws IOException {
        byte[] data = java.nio.file.Files.readAllBytes(inputPdf.toPath());
        try (PDDocument doc = PDDocument.load(data)) {
            stripPageNumbers(doc);
            if (region.firstClaimPage >= 0 && region.firstClaimYDirAdj > 0) {
                stripAllContentAbove(doc, region.firstClaimPage, region.firstClaimYDirAdj);
            }
            doc.save(outputPdf);
        }
    }

    /**
     * Produce un PDF con SOLO la 1ª reivindicación, preservando 100% del contenido
     * original: texto, figuras, formas, imágenes, viñetas, espaciado, fuentes.
     *
     * A diferencia de generateFirstClaimPdf (que manipula el content stream y puede
     * perder figuras/formas), este método usa SOLO:
     *   - Eliminación de páginas antes/después del rango de la 1ª reivindicación
     *   - CropBox para ocultar el área por encima de la 1ª reiv y debajo de la 2ª
     *   - Limpieza de números de página (único cambio al content stream, seguro)
     *
     * Al no modificar el content stream (excepto page numbers), se preserva
     * absolutamente todo el contenido gráfico y textual del original.
     *
     * @return true si se generó exitosamente
     */
    public boolean generateFirstClaimPreserved(File inputClaimsPdf, File outputPdf)
            throws Exception {
        // Primero localizar la 1ª reivindicación (solo lectura)
        ClaimRegion region = getFirstClaimRegion(inputClaimsPdf);
        if (region == null) return false;

        try (PDDocument doc = PDDocument.load(inputClaimsPdf)) {
            int totalPages = doc.getNumberOfPages();

            // ── Limpiar números de página de todas las páginas ──
            stripPageNumbers(doc);

            // ── Determinar rango de páginas a conservar ──
            int lastPage = (region.secondClaimPage >= 0)
                    ? region.secondClaimPage
                    : totalPages - 1;

            // ── Eliminar páginas DESPUÉS del rango ──
            for (int i = totalPages - 1; i > lastPage; i--) {
                doc.removePage(i);
            }

            // ── CropBox en la última página: ocultar contenido debajo de la 2ª reiv ──
            if (region.secondClaimPage >= 0 && region.secondClaimYDirAdj > 0) {
                int lastIdx = Math.min(lastPage, doc.getNumberOfPages() - 1);
                PDPage page = doc.getPage(lastIdx);
                PDRectangle mb = page.getMediaBox();
                float pageHeight = mb.getHeight();

                // DirAdj Y crece hacia abajo; convertir a PDF coords (desde LLY)
                // Cortar por encima del inicio de la 2ª reivindicación
                // +2pt para excluir el "2." sin perder la última línea de la reiv 1
                float cropTopPdfY = pageHeight - region.secondClaimYDirAdj + mb.getLowerLeftY() + 2;

                PDRectangle crop = new PDRectangle(
                        mb.getLowerLeftX(),
                        cropTopPdfY,
                        mb.getWidth(),
                        mb.getLowerLeftY() + pageHeight - cropTopPdfY);
                page.setMediaBox(crop);
                page.setCropBox(crop);
            }

            // ── Eliminar páginas ANTES del rango ──
            for (int i = region.firstClaimPage - 1; i >= 0; i--) {
                doc.removePage(i);
            }

            // ── CropBox en la primera página: ocultar encabezados/títulos ──
            if (region.firstClaimYDirAdj > 0 && doc.getNumberOfPages() > 0) {
                PDPage page = doc.getPage(0);
                PDRectangle mb = page.getMediaBox();
                float pageHeight = mb.getHeight();

                // Dejar 20pt de margen encima del "1." de la reivindicación
                float claimStartPdfY = pageHeight - region.firstClaimYDirAdj + mb.getLowerLeftY() + 20;

                // Solo recortar si hay más de 40pt de espacio vacío por encima
                float currentTop = mb.getLowerLeftY() + pageHeight;
                if (currentTop - claimStartPdfY > 40) {
                    // Obtener o preservar el CropBox existente (por si ya lo ajustamos en el paso anterior)
                    PDRectangle existingCrop = page.getCropBox();
                    float bottomY = existingCrop.getLowerLeftY();

                    PDRectangle crop = new PDRectangle(
                            mb.getLowerLeftX(),
                            bottomY,
                            mb.getWidth(),
                            claimStartPdfY - bottomY);
                    page.setMediaBox(crop);
                    page.setCropBox(crop);
                }
            }

            doc.save(outputPdf);
            return true;
        }
    }

    /** Segmento de texto con info de formato (bold/normal, posición, tamaño) */
    public static class FormattedSegment {
        public final String text;
        public final boolean bold;
        public final float xPos;      // X de primer carácter (0 para "\n")
        public final float lineHeight; // tamaño de fuente de esta línea (0 para "\n")

        public FormattedSegment(String text, boolean bold) {
            this(text, bold, 0f, 0f);
        }

        public FormattedSegment(String text, boolean bold, float xPos, float lineHeight) {
            this.text = text;
            this.bold = bold;
            this.xPos = xPos;
            this.lineHeight = lineHeight;
        }
    }

    /** Resultado de extracción: segmentos formateados + fuente detectada */
    public static class ClaimResult {
        public final String text;
        public final java.util.List<FormattedSegment> segments;
        public final String fontFamily;
        public final float fontSize;
        public final float measuredLeading;
        public final boolean justified;         // true si el texto original era justificado
        public final float originalLeftMargin;  // margen izquierdo del original (pt)
        public final float originalRightMargin; // margen derecho del original (pt)

        public ClaimResult(String text, java.util.List<FormattedSegment> segments,
                           String fontFamily, float fontSize, float measuredLeading,
                           boolean justified, float originalLeftMargin, float originalRightMargin) {
            this.text = text;
            this.segments = segments;
            this.fontFamily = fontFamily;
            this.fontSize = fontSize;
            this.measuredLeading = measuredLeading;
            this.justified = justified;
            this.originalLeftMargin = originalLeftMargin;
            this.originalRightMargin = originalRightMargin;
        }
    }

    /**
     * Extrae la 1ª reivindicación preservando formato (bold/normal por línea)
     * + detecta la fuente/tamaño dominante del documento.
     */
    public ClaimResult extractFirstClaim(File inputClaimsPdf) throws IOException {
        try (PDDocument doc = PDDocument.load(inputClaimsPdf)) {
            int totalPages = doc.getNumberOfPages();
            if (totalPages == 0) return null;

            // Detectar fuente dominante
            FontInfoStripper fontDetector = new FontInfoStripper();
            fontDetector.setSortByPosition(true);
            fontDetector.getText(doc);
            String detectedFamily = fontDetector.getDominantFamily();
            float detectedSize = fontDetector.getDominantSize();

            // Extraer todas las líneas con info de formato (bold/normal)
            FormattedClaimStripper fcs = new FormattedClaimStripper();
            fcs.setSortByPosition(true);
            fcs.getText(doc);
            List<FormattedSegment> allSegments = fcs.getSegments();

            // Reconstruir texto plano para encontrar límites de reivindicación
            StringBuilder sb = new StringBuilder();
            for (FormattedSegment seg : allSegments) sb.append(seg.text);
            String fullText = sb.toString();
            if (fullText.trim().isEmpty()) return null;

            String claimFormat = detectClaimFormat(fullText);

            // Buscar inicio de 1ª reivindicación
            Pattern firstP = Pattern.compile(
                    "(?m)^\\s*1[ªº]?\\s*" + Pattern.quote(claimFormat) + "\\s*");
            Matcher firstM = firstP.matcher(fullText);
            int startCharIdx;
            if (firstM.find()) {
                startCharIdx = firstM.start();
            } else {
                Matcher fb = FIRST_CLAIM_DETECTOR.matcher(fullText);
                if (fb.find()) {
                    startCharIdx = fb.start();
                } else {
                    // Fallback: formato "Reivindicación 1"
                    Matcher reivFb = REIV_FIRST_CLAIM.matcher(fullText);
                    if (reivFb.find()) startCharIdx = reivFb.start();
                    else return null;
                }
            }

            // Buscar inicio de 2ª reivindicación
            Pattern secondP = Pattern.compile(
                    "(?m)^\\s*2[ªº]?\\s*" + Pattern.quote(claimFormat) + "\\s*");
            Matcher secondM = secondP.matcher(fullText);
            int endCharIdx = fullText.length();
            if (secondM.find(startCharIdx + 1)) {
                endCharIdx = secondM.start();
            } else {
                Matcher fb2 = GENERIC_SECOND_CLAIM.matcher(fullText);
                if (fb2.find(startCharIdx + 1)) {
                    endCharIdx = fb2.start();
                } else {
                    // Fallback: formato "Reivindicación 2"
                    Matcher reivFb2 = REIV_SECOND_CLAIM.matcher(fullText);
                    if (reivFb2.find(startCharIdx + 1)) endCharIdx = reivFb2.start();
                }
            }

            // Mapear índices de caracteres a índices de segmentos
            int charPos = 0;
            int startSeg = -1, endSeg = allSegments.size();
            for (int i = 0; i < allSegments.size(); i++) {
                int segEnd = charPos + allSegments.get(i).text.length();
                if (startSeg < 0 && segEnd > startCharIdx) startSeg = i;
                if (charPos >= endCharIdx && endSeg == allSegments.size()) {
                    endSeg = i;
                    break;
                }
                charPos = segEnd;
            }
            if (startSeg < 0) return null;

            // Recoger segmentos de la 1ª reivindicación, omitir números de página
            List<FormattedSegment> claimSegments = new ArrayList<>();
            for (int i = startSeg; i < endSeg; i++) {
                FormattedSegment seg = allSegments.get(i);
                // Omitir líneas que solo son números (números de página/margen)
                if (!"\n".equals(seg.text) && seg.text.trim().matches("\\d{1,4}")) continue;
                claimSegments.add(seg);
            }

            // Texto plano para compatibilidad
            StringBuilder plainBuilder = new StringBuilder();
            for (FormattedSegment seg : claimSegments) plainBuilder.append(seg.text);
            String plainText = cleanText(plainBuilder.toString());

            float measuredLeading = fcs.getMeasuredLeading();
            boolean justified = fcs.isJustified();
            float originalLeft = fcs.getMinX();
            float originalRight = fcs.getMaxRightEdge();

            return new ClaimResult(plainText, claimSegments, detectedFamily, detectedSize,
                    measuredLeading, justified, originalLeft, originalRight);
        }
    }

    /** Extracción interna del texto de la 1ª reivindicación */
    private String extractFirstClaimTextInternal(PDDocument doc, int totalPages) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        String fullText = stripper.getText(doc);
        if (fullText == null || fullText.trim().isEmpty()) return null;

        String claimFormat = detectClaimFormat(fullText);

        Pattern firstClaimPattern = Pattern.compile(
                "(?m)^\\s*1[ªº]?\\s*" + Pattern.quote(claimFormat) + "\\s*");
        Matcher firstMatcher = firstClaimPattern.matcher(fullText);

        int startIdx;
        if (firstMatcher.find()) {
            startIdx = firstMatcher.start();
        } else {
            Matcher fallback = FIRST_CLAIM_DETECTOR.matcher(fullText);
            if (fallback.find()) {
                startIdx = fallback.start();
            } else {
                // Fallback: formato "Reivindicación 1"
                Matcher reivFb = REIV_FIRST_CLAIM.matcher(fullText);
                if (reivFb.find()) {
                    startIdx = reivFb.start();
                } else {
                    return cleanText(fullText.trim());
                }
            }
        }

        Pattern secondClaimPattern = Pattern.compile(
                "(?m)^\\s*2[ªº]?\\s*" + Pattern.quote(claimFormat) + "\\s*");
        Matcher secondMatcher = secondClaimPattern.matcher(fullText);

        int endIdx = fullText.length();
        if (secondMatcher.find(startIdx + 1)) {
            endIdx = secondMatcher.start();
        } else {
            Matcher fallback2 = GENERIC_SECOND_CLAIM.matcher(fullText);
            if (fallback2.find(startIdx + 1)) {
                endIdx = fallback2.start();
            } else {
                // Fallback: formato "Reivindicación 2"
                Matcher reivFb2 = REIV_SECOND_CLAIM.matcher(fullText);
                if (reivFb2.find(startIdx + 1)) endIdx = reivFb2.start();
            }
        }

        return cleanText(fullText.substring(startIdx, endIdx).trim());
    }

    // ══════════════════════════════════════════════════════════════════
    // ── MÉTODO PRINCIPAL: PDF recortado con formato original ─────────
    // ══════════════════════════════════════════════════════════════════

    /**
     * Produce un PDF con SOLO la primera reivindicación, preservando el formato
     * original del PDF fuente. Elimina físicamente:
     * - Todo contenido debajo de la 2ª reivindicación (texto, imágenes, gráficos)
     * - Todo contenido encima de la 1ª reivindicación (encabezados, títulos)
     * - Números de página
     * - Páginas sobrantes
     *
     * @return true si se generó exitosamente
     */
    public boolean generateFirstClaimPdf(File inputClaimsPdf, File outputPdf) throws Exception {
        try (PDDocument doc = PDDocument.load(inputClaimsPdf)) {
            int totalPages = doc.getNumberOfPages();
            if (totalPages == 0) return false;

            String claimFormat = detectClaimFormat(doc, totalPages);

            // ── Eliminar números de página de todas las páginas ──
            stripPageNumbers(doc);

            // ── Localizar 1ª reivindicación ──
            int firstClaimPage = findPageWithPattern(doc, totalPages, FIRST_CLAIM_DETECTOR);
            boolean reivFormat = false;
            if (firstClaimPage < 0) {
                firstClaimPage = findPageWithPattern(doc, totalPages, REIV_FIRST_CLAIM);
                reivFormat = firstClaimPage >= 0;
            }
            // Compatibilidad: formato "R1:" / "R1."
            boolean rClaimFormat = false;
            if (firstClaimPage < 0) {
                firstClaimPage = findPageWithPattern(doc, totalPages, R_CLAIM_FIRST_DETECTOR);
                rClaimFormat = firstClaimPage >= 0;
            }
            if (firstClaimPage < 0) return false;

            float firstClaimYDirAdj;
            if (rClaimFormat) {
                firstClaimYDirAdj = findYPosition(doc, firstClaimPage, R_CLAIM_FIRST_LINE);
            } else if (reivFormat) {
                firstClaimYDirAdj = findYPosition(doc, firstClaimPage, REIV_FIRST_CLAIM_LINE);
            } else {
                firstClaimYDirAdj = findYPosition(doc, firstClaimPage, FIRST_CLAIM_LINE);
                if (firstClaimYDirAdj < 0) {
                    firstClaimYDirAdj = findYPosition(doc, firstClaimPage,
                            Pattern.compile("^\\s*1[ªº]?\\s*" + Pattern.quote(claimFormat)));
                }
                if (firstClaimYDirAdj < 0) {
                    firstClaimYDirAdj = findYPosition(doc, firstClaimPage, REIV_FIRST_CLAIM_LINE);
                    reivFormat = true;
                }
            }

            // ── Localizar 2ª reivindicación ──
            int secondClaimPage;
            if (rClaimFormat) {
                secondClaimPage = findPageWithPattern(doc, totalPages, R_CLAIM_SECOND_DETECTOR);
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithPattern(doc, totalPages, GENERIC_SECOND_CLAIM_NO_PAREN);
                }
            } else if (reivFormat) {
                secondClaimPage = findPageWithPattern(doc, totalPages, REIV_SECOND_CLAIM);
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithPattern(doc, totalPages, GENERIC_SECOND_CLAIM_NO_PAREN);
                }
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithGenericSecondClaim(doc, totalPages, claimFormat);
                }
            } else {
                Pattern secondPattern = buildSecondClaimPattern(claimFormat);
                secondClaimPage = findPageWithPattern(doc, totalPages, secondPattern);
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithPattern(doc, totalPages, GENERIC_SECOND_CLAIM_NO_PAREN);
                }
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithGenericSecondClaim(doc, totalPages, claimFormat);
                }
                if (secondClaimPage < 0) {
                    secondClaimPage = findPageWithPattern(doc, totalPages, REIV_SECOND_CLAIM);
                }
            }

            float secondClaimYDirAdj = -1;
            if (secondClaimPage >= 0) {
                if (rClaimFormat) {
                    secondClaimYDirAdj = findYPosition(doc, secondClaimPage, R_CLAIM_SECOND_LINE);
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE_NO_PAREN);
                    }
                } else if (reivFormat) {
                    secondClaimYDirAdj = findYPosition(doc, secondClaimPage, REIV_SECOND_CLAIM_LINE);
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE_NO_PAREN);
                    }
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE);
                    }
                } else {
                    Pattern lineP = buildSecondClaimLinePattern(claimFormat);
                    secondClaimYDirAdj = findYPosition(doc, secondClaimPage, lineP);
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE_NO_PAREN);
                    }
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, GENERIC_SECOND_CLAIM_LINE);
                    }
                    if (secondClaimYDirAdj < 0) {
                        secondClaimYDirAdj = findYPosition(doc, secondClaimPage, REIV_SECOND_CLAIM_LINE);
                    }
                }
            }

            // ── Eliminar páginas posteriores a la 2ª reivindicación ──
            int lastPage = (secondClaimPage >= 0) ? secondClaimPage : totalPages - 1;
            for (int i = totalPages - 1; i > lastPage; i--) {
                doc.removePage(i);
            }

            // ── Borrar todo debajo de la 2ª reivindicación ──
            if (secondClaimPage >= 0 && secondClaimYDirAdj > 0) {
                // Ajustar índice después de haber eliminado páginas posteriores
                int adjustedIdx = Math.min(secondClaimPage, doc.getNumberOfPages() - 1);
                stripAllContentBelow(doc, adjustedIdx, secondClaimYDirAdj);
            }

            // ── Eliminar páginas anteriores a la 1ª reivindicación ──
            for (int i = firstClaimPage - 1; i >= 0; i--) {
                doc.removePage(i);
            }

            // ── Borrar contenido ENCIMA de la 1ª reivindicación (encabezados, títulos) ──
            // NO recortar mediaBox — solo eliminar contenido del content stream
            if (firstClaimYDirAdj > 0 && doc.getNumberOfPages() > 0) {
                stripAllContentAbove(doc, 0, firstClaimYDirAdj);
            }

            doc.save(outputPdf);
            return true;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Extracción de texto (legacy - ahora se usa extractFirstClaim) ─
    // ══════════════════════════════════════════════════════════════════

    /**
     * @deprecated Usar extractFirstClaim() que además detecta la fuente.
     */
    public String extractFirstClaimText(File inputClaimsPdf) throws IOException {
        ClaimResult result = extractFirstClaim(inputClaimsPdf);
        return result != null ? result.text : null;
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Método legacy (para Tab 2 de carga manual) ───────────────────
    // ══════════════════════════════════════════════════════════════════

    public void generateFirstLiteralOnly(File inputClaimsPdf, File outputPdf) throws Exception {
        if (!generateFirstClaimPdf(inputClaimsPdf, outputPdf)) {
            // Fallback: copiar el original
            java.nio.file.Files.copy(inputClaimsPdf.toPath(), outputPdf.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Eliminación de contenido del content stream ──────────────────
    // ══════════════════════════════════════════════════════════════════

    /**
     * Elimina FÍSICAMENTE todo el contenido debajo de la posición Y indicada:
     * texto (Tj, TJ, '), imágenes (Do), rectángulos (re), líneas y curvas.
     * Recorta el mediaBox inferior.
     */
    private void stripAllContentBelow(PDDocument doc, int pageIndex, float yDirAdj)
            throws IOException {

        PDPage page = doc.getPage(pageIndex);
        PDRectangle mb = page.getMediaBox();
        float pageHeight = mb.getHeight();

        float cutoffPdfY = pageHeight - yDirAdj + 15;

        if (cutoffPdfY >= pageHeight - 40) {
            doc.removePage(pageIndex);
            return;
        }

        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();
        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>(tokens.size());

        boolean inTextBlock = false;
        float curX = 0, curY = 0;
        boolean stripText = false;
        float lastCmTy = 0;

        for (Object token : tokens) {
            if (token instanceof Operator) {
                Operator op = (Operator) token;
                String name = op.getName();

                switch (name) {
                    case "BT":
                        inTextBlock = true;
                        curX = 0; curY = 0;
                        stripText = false;
                        newTokens.add(token);
                        break;

                    case "ET":
                        inTextBlock = false;
                        stripText = false;
                        newTokens.add(token);
                        break;

                    case "cm": {
                        int sz = newTokens.size();
                        if (sz >= 6) {
                            lastCmTy = floatVal(newTokens.get(sz - 1));
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "Tm": {
                        int sz = newTokens.size();
                        if (inTextBlock && sz >= 6) {
                            curX = floatVal(newTokens.get(sz - 2));
                            curY = floatVal(newTokens.get(sz - 1));
                            stripText = curY < cutoffPdfY;
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "Td":
                    case "TD": {
                        int sz = newTokens.size();
                        if (inTextBlock && sz >= 2) {
                            curX += floatVal(newTokens.get(sz - 2));
                            curY += floatVal(newTokens.get(sz - 1));
                            stripText = curY < cutoffPdfY;
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "Tj": {
                        if (stripText) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSString) {
                                newTokens.set(sz - 1, new COSString(""));
                            }
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "TJ": {
                        if (stripText) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSArray) {
                                COSArray arr = (COSArray) newTokens.get(sz - 1);
                                COSArray clean = new COSArray();
                                for (int j = 0; j < arr.size(); j++) {
                                    COSBase item = arr.get(j);
                                    if (item instanceof COSString) {
                                        clean.add(new COSString(""));
                                    } else {
                                        clean.add(item);
                                    }
                                }
                                newTokens.set(sz - 1, clean);
                            }
                        }
                        newTokens.add(token);
                        break;
                    }

                    case "'": {
                        if (stripText) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSString) {
                                newTokens.set(sz - 1, new COSString(""));
                            }
                        }
                        newTokens.add(token);
                        break;
                    }

                    // Eliminar rectángulos debajo del corte
                    case "re": {
                        int sz = newTokens.size();
                        if (sz >= 4) {
                            float rectY = floatVal(newTokens.get(sz - 3));
                            float rectH = floatVal(newTokens.get(sz - 1));
                            float rectTop = rectY + rectH;
                            if (rectTop < cutoffPdfY) {
                                for (int k = 0; k < 4; k++) newTokens.remove(sz - 4);
                                break;
                            }
                        }
                        newTokens.add(token);
                        break;
                    }

                    // Eliminar imágenes/XObjects posicionados debajo del corte
                    case "Do": {
                        if (lastCmTy > 0 && lastCmTy < cutoffPdfY) {
                            int sz = newTokens.size();
                            if (sz >= 1) {
                                newTokens.remove(sz - 1);
                            }
                            break;
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

        // Reescribir el content stream
        PDStream newStream = new PDStream(doc);
        try (OutputStream out = newStream.createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter writer = new ContentStreamWriter(out);
            writer.writeTokens(newTokens);
        }
        page.setContents(newStream);

        // Recortar mediaBox inferior
        page.setMediaBox(new PDRectangle(
                mb.getLowerLeftX(), cutoffPdfY,
                mb.getWidth(), pageHeight - cutoffPdfY));
        page.setCropBox(page.getMediaBox());
    }

    /**
     * Elimina FÍSICAMENTE todo el contenido ENCIMA de la posición Y indicada.
     * yDirAdj es la coordenada en espacio "dir adj" (PDFTextStripper).
     * Borra texto (Tj, TJ, ') cuya Y esté por encima (valor Y menor en DirAdj).
     */
    private void stripAllContentAbove(PDDocument doc, int pageIndex, float yDirAdj)
            throws IOException {
        PDPage page = doc.getPage(pageIndex);
        PDRectangle mb = page.getMediaBox();
        float pageHeight = mb.getHeight();

        // En PDF coords: Y crece hacia arriba. DirAdj Y crece hacia abajo.
        // cutoffPdfY = posición en PDF coords encima de la cual BORRAR
        float cutoffPdfY = pageHeight - yDirAdj + mb.getLowerLeftY() + 15; // 15pt margen

        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();
        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>(tokens.size());

        boolean inTextBlock = false;
        float curY = 0;
        boolean stripText = false;

        for (Object token : tokens) {
            if (token instanceof Operator) {
                Operator op = (Operator) token;
                String name = op.getName();

                switch (name) {
                    case "BT":
                        inTextBlock = true; curY = 0; stripText = false;
                        newTokens.add(token);
                        break;
                    case "ET":
                        inTextBlock = false; stripText = false;
                        newTokens.add(token);
                        break;
                    case "Tm": {
                        int sz = newTokens.size();
                        if (inTextBlock && sz >= 6) {
                            curY = floatVal(newTokens.get(sz - 1));
                            stripText = curY > cutoffPdfY;
                        }
                        newTokens.add(token);
                        break;
                    }
                    case "Td": case "TD": {
                        int sz = newTokens.size();
                        if (inTextBlock && sz >= 2) {
                            curY += floatVal(newTokens.get(sz - 1));
                            stripText = curY > cutoffPdfY;
                        }
                        newTokens.add(token);
                        break;
                    }
                    case "Tj": {
                        if (stripText) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSString)
                                newTokens.set(sz - 1, new COSString(""));
                        }
                        newTokens.add(token);
                        break;
                    }
                    case "TJ": {
                        if (stripText) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSArray) {
                                COSArray arr = (COSArray) newTokens.get(sz - 1);
                                COSArray clean = new COSArray();
                                for (int j = 0; j < arr.size(); j++) {
                                    COSBase item = arr.get(j);
                                    if (item instanceof COSString) clean.add(new COSString(""));
                                    else clean.add(item);
                                }
                                newTokens.set(sz - 1, clean);
                            }
                        }
                        newTokens.add(token);
                        break;
                    }
                    case "'": {
                        if (stripText) {
                            int sz = newTokens.size();
                            if (sz >= 1 && newTokens.get(sz - 1) instanceof COSString)
                                newTokens.set(sz - 1, new COSString(""));
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
        try (OutputStream out = newStream.createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter writer = new ContentStreamWriter(out);
            writer.writeTokens(newTokens);
        }
        page.setContents(newStream);
    }

    /**
     * Elimina FÍSICAMENTE los números de página de todas las páginas.
     * Busca texto aislado en la zona superior/inferior (~60pt) que sea solo dígitos,
     * y también números de línea en el margen izquierdo (múltiplos de 5 a X &lt; 45pt).
     */
    private void stripPageNumbers(PDDocument doc) throws IOException {
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            PDPage page = doc.getPage(p);
            PDRectangle mb = page.getMediaBox();
            float pageTop = mb.getLowerLeftY() + mb.getHeight();
            float topThreshold = pageTop - 60;
            float bottomThreshold = mb.getLowerLeftY() + 60;
            float leftMarginThreshold = mb.getLowerLeftX() + 45;

            PDFStreamParser parser = new PDFStreamParser(page);
            parser.parse();
            List<Object> tokens = parser.getTokens();
            List<Object> newTokens = new ArrayList<>(tokens.size());

            boolean inText = false;
            float curX = 0, curY = 0;
            boolean isTopBottomMargin = false;
            boolean isLeftMargin = false;

            for (Object token : tokens) {
                if (token instanceof Operator) {
                    Operator op = (Operator) token;
                    String name = op.getName();

                    switch (name) {
                        case "BT":
                            inText = true; curX = 0; curY = 0;
                            isTopBottomMargin = false; isLeftMargin = false;
                            newTokens.add(token);
                            break;
                        case "ET":
                            inText = false;
                            newTokens.add(token);
                            break;
                        case "Tm": {
                            int sz = newTokens.size();
                            if (inText && sz >= 6) {
                                curX = floatVal(newTokens.get(sz - 2));
                                curY = floatVal(newTokens.get(sz - 1));
                                isTopBottomMargin = curY > topThreshold || curY < bottomThreshold;
                                isLeftMargin = curX < leftMarginThreshold;
                            }
                            newTokens.add(token);
                            break;
                        }
                        case "Td": case "TD": {
                            int sz = newTokens.size();
                            if (inText && sz >= 2) {
                                curX += floatVal(newTokens.get(sz - 2));
                                curY += floatVal(newTokens.get(sz - 1));
                                isTopBottomMargin = curY > topThreshold || curY < bottomThreshold;
                                isLeftMargin = curX < leftMarginThreshold;
                            }
                            newTokens.add(token);
                            break;
                        }
                        case "Tj": {
                            if (isTopBottomMargin || isLeftMargin) {
                                int sz = newTokens.size();
                                if (sz >= 1 && newTokens.get(sz - 1) instanceof COSString) {
                                    String txt = ((COSString) newTokens.get(sz - 1))
                                            .getString().trim();
                                    if (txt.matches("\\d{1,4}") && shouldStripDigit(
                                            txt, isTopBottomMargin, isLeftMargin)) {
                                        newTokens.set(sz - 1, new COSString(""));
                                    }
                                }
                            }
                            newTokens.add(token);
                            break;
                        }
                        case "TJ": {
                            if (isTopBottomMargin || isLeftMargin) {
                                int sz = newTokens.size();
                                if (sz >= 1 && newTokens.get(sz - 1) instanceof COSArray) {
                                    COSArray arr = (COSArray) newTokens.get(sz - 1);
                                    StringBuilder sb = new StringBuilder();
                                    for (int j = 0; j < arr.size(); j++) {
                                        COSBase item = arr.get(j);
                                        if (item instanceof COSString)
                                            sb.append(((COSString) item).getString());
                                    }
                                    String combined = sb.toString().trim();
                                    if (combined.matches("\\d{1,4}") && shouldStripDigit(
                                            combined, isTopBottomMargin, isLeftMargin)) {
                                        COSArray clean = new COSArray();
                                        for (int j = 0; j < arr.size(); j++) {
                                            COSBase item = arr.get(j);
                                            if (item instanceof COSString)
                                                clean.add(new COSString(""));
                                            else clean.add(item);
                                        }
                                        newTokens.set(sz - 1, clean);
                                    }
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
            try (OutputStream out = newStream.createOutputStream(COSName.FLATE_DECODE)) {
                ContentStreamWriter writer = new ContentStreamWriter(out);
                writer.writeTokens(newTokens);
            }
            page.setContents(newStream);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Helpers ──────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════

    /**
     * Determina si un dígito aislado debe ser eliminado.
     * Zona superior/inferior: cualquier dígito de 1-4 cifras (es número de página).
     * Margen izquierdo: solo múltiplos de 5 ≤ 35 (números de línea típicos: 5,10,...,35).
     */
    private boolean shouldStripDigit(String digitText, boolean isTopBottom, boolean isLeft) {
        if (isTopBottom) return true;
        if (isLeft) {
            try {
                int val = Integer.parseInt(digitText);
                return val > 0 && val <= 35 && val % 5 == 0;
            } catch (NumberFormatException e) { return false; }
        }
        return false;
    }

    /**
     * Busca la 2ª reivindicación con GENERIC_SECOND_CLAIM pero con guardia
     * contra sub-ítems. Si la reivindicación 1 usa separador "." (no ")"),
     * y GENERIC encuentra "2)" en una página que también tiene "1)",
     * asume que "2)" es un sub-ítem y continúa buscando en páginas siguientes.
     */
    private int findPageWithGenericSecondClaim(PDDocument doc, int totalPages,
            String claim1Sep) throws IOException {
        boolean guardSubItems = claim1Sep != null && !claim1Sep.contains(")");
        Pattern subItem1 = Pattern.compile("(?m)^\\s*1[ªº]?\\s*\\)");
        PDFTextStripper stripper = new PDFTextStripper();
        for (int p = 0; p < totalPages; p++) {
            stripper.setStartPage(p + 1);
            stripper.setEndPage(p + 1);
            String pageText = stripper.getText(doc);
            Matcher m = GENERIC_SECOND_CLAIM.matcher(pageText);
            if (m.find()) {
                if (guardSubItems) {
                    String match = m.group().trim();
                    // Si el match usa ")" y la página tiene "1)" → sub-ítem, saltar
                    if (match.matches("2[ªº]?\\s*\\).*")
                            && subItem1.matcher(pageText).find()) {
                        continue;
                    }
                }
                return p;
            }
        }
        return -1;
    }

    private String detectClaimFormat(PDDocument doc, int totalPages) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        for (int p = 0; p < totalPages; p++) {
            stripper.setStartPage(p + 1);
            stripper.setEndPage(p + 1);
            String pageText = stripper.getText(doc);
            Matcher m = FIRST_CLAIM_DETECTOR.matcher(pageText);
            if (m.find()) {
                return m.group().trim().substring(1);
            }
        }
        return ".";
    }

    private String detectClaimFormat(String fullText) {
        Matcher m = FIRST_CLAIM_DETECTOR.matcher(fullText);
        if (m.find()) {
            String match = m.group().trim();
            if (match.length() > 1) return match.substring(1);
        }
        return ".";
    }

    private Pattern buildSecondClaimPattern(String format) {
        return Pattern.compile("(?m)^\\s*2" + Pattern.quote(format) + "\\s*");
    }

    private Pattern buildSecondClaimLinePattern(String format) {
        return Pattern.compile("^(\\d{1,3}\\s+)?2" + Pattern.quote(format));
    }

    private int findPageWithPattern(PDDocument doc, int totalPages, Pattern pattern)
            throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        for (int p = 0; p < totalPages; p++) {
            stripper.setStartPage(p + 1);
            stripper.setEndPage(p + 1);
            String pageText = stripper.getText(doc);
            if (pattern.matcher(pageText).find()) {
                return p;
            }
        }
        return -1;
    }

    private float findYPosition(PDDocument doc, int pageIndex, Pattern linePattern)
            throws IOException {
        ClaimYFinder finder = new ClaimYFinder(linePattern);
        finder.setSortByPosition(true);
        finder.setStartPage(pageIndex + 1);
        finder.setEndPage(pageIndex + 1);
        finder.getText(doc);
        return finder.getY();
    }

    private String cleanText(String text) {
        if (text == null) return "";
        text = text.replaceAll("(?m)^\\s*\\d{1,3}\\s*$", "");
        text = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private static float floatVal(Object obj) {
        if (obj instanceof COSFloat)   return ((COSFloat) obj).floatValue();
        if (obj instanceof COSInteger) return ((COSInteger) obj).floatValue();
        return 0;
    }

    // ── Buscador de posición Y de una reivindicación ────────────────

    private static class ClaimYFinder extends PDFTextStripper {

        private final Pattern linePattern;
        private float y = -1;

        ClaimYFinder(Pattern linePattern) throws IOException {
            super();
            this.linePattern = linePattern;
        }

        float getY() { return y; }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions)
                throws IOException {
            if (y < 0 && !textPositions.isEmpty()) {
                String trimmed = text.trim();
                Matcher m = linePattern.matcher(trimmed);
                if (m.find()) {
                    String marginPrefix = m.group(1);
                    int offsetInTrimmed = (marginPrefix != null) ? marginPrefix.length() : 0;
                    int leadingSpaces = text.length() - text.stripLeading().length();
                    int posInOriginal = leadingSpaces + offsetInTrimmed;

                    if (posInOriginal < textPositions.size()) {
                        y = textPositions.get(posInOriginal).getYDirAdj();
                    } else if (!textPositions.isEmpty()) {
                        y = textPositions.get(0).getYDirAdj();
                    }
                }
            }
            super.writeString(text, textPositions);
        }
    }

    // ── Extractor de texto con formato (intra-línea bold/normal + alineación) ──

    private static class FormattedClaimStripper extends PDFTextStripper {

        private final List<FormattedSegment> segments = new ArrayList<>();
        private final List<Float> yPositions = new ArrayList<>();
        private final List<Float> rightEdges = new ArrayList<>();
        private float minX = Float.MAX_VALUE;
        private float maxRightEdge = 0;

        FormattedClaimStripper() throws IOException { super(); }

        List<FormattedSegment> getSegments() { return segments; }
        float getMinX() { return minX == Float.MAX_VALUE ? 0 : minX; }
        float getMaxRightEdge() { return maxRightEdge; }

        /** Calcula el interlineado promedio medido del documento original */
        float getMeasuredLeading() {
            if (yPositions.size() < 2) return 0;
            float totalDelta = 0;
            int count = 0;
            for (int i = 1; i < yPositions.size(); i++) {
                float delta = yPositions.get(i) - yPositions.get(i - 1);
                if (delta > 2 && delta < 40) {
                    totalDelta += delta;
                    count++;
                }
            }
            return count > 0 ? totalDelta / count : 0;
        }

        /** Detecta si el texto usa alineación justificada */
        boolean isJustified() {
            if (rightEdges.size() < 4) return false;
            // Excluir la última línea de cada párrafo (suele ser corta)
            // Contar cuántas líneas terminan cerca del borde derecho máximo
            float maxRight = 0;
            for (float r : rightEdges) maxRight = Math.max(maxRight, r);
            int nearRight = 0;
            int total = rightEdges.size();
            for (float r : rightEdges) {
                if (maxRight - r < 15) nearRight++; // dentro de 15pt del borde
            }
            // Si >60% de las líneas llegan cerca del borde derecho → justificado
            return nearRight > total * 0.6;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions)
                throws IOException {
            if (textPositions == null || textPositions.isEmpty()) {
                segments.add(new FormattedSegment(text, false, 0f, 0f));
                super.writeString(text, textPositions);
                return;
            }

            float lineX = textPositions.get(0).getXDirAdj();
            yPositions.add(textPositions.get(0).getYDirAdj());

            // Margen izquierdo mínimo
            if (lineX > 0) minX = Math.min(minX, lineX);

            // Borde derecho de la línea
            TextPosition lastTp = textPositions.get(textPositions.size() - 1);
            float rightEdge = lastTp.getXDirAdj() + lastTp.getWidthDirAdj();
            rightEdges.add(rightEdge);
            maxRightEdge = Math.max(maxRightEdge, rightEdge);

            // ── Producir segmentos intra-línea: dividir por cambios bold/normal ──
            StringBuilder runText = new StringBuilder();
            boolean runBold = isBoldChar(textPositions.get(0));
            float runX = lineX;
            float runFontSize = textPositions.get(0).getFontSizeInPt();

            for (int i = 0; i < textPositions.size(); i++) {
                TextPosition tp = textPositions.get(i);
                boolean curBold = isBoldChar(tp);

                if (curBold != runBold && runText.length() > 0) {
                    // Cambio de formato → emitir run actual
                    segments.add(new FormattedSegment(runText.toString(), runBold, runX, runFontSize));
                    runText = new StringBuilder();
                    runBold = curBold;
                    runX = tp.getXDirAdj();
                    runFontSize = tp.getFontSizeInPt();
                }

                runText.append(tp.getUnicode());
            }

            // Emitir último run de la línea
            if (runText.length() > 0) {
                segments.add(new FormattedSegment(runText.toString(), runBold, runX, runFontSize));
            }

            super.writeString(text, textPositions);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            segments.add(new FormattedSegment("\n", false));
            super.writeLineSeparator();
        }

        private boolean isBoldChar(TextPosition tp) {
            String fn = tp.getFont().getName().toLowerCase();
            return fn.contains("bold");
        }
    }

    // ── Detector de fuente dominante ────────────────────────────────

    private static class FontInfoStripper extends PDFTextStripper {

        private final java.util.Map<String, Integer> familyCounts = new java.util.LinkedHashMap<>();
        private final java.util.Map<Float, Integer> sizeCounts = new java.util.LinkedHashMap<>();

        FontInfoStripper() throws IOException { super(); }

        String getDominantFamily() {
            String best = "Times";
            int max = 0;
            for (java.util.Map.Entry<String, Integer> e : familyCounts.entrySet()) {
                if (e.getValue() > max) {
                    max = e.getValue();
                    best = e.getKey();
                }
            }
            return best;
        }

        float getDominantSize() {
            float best = 11f;
            int max = 0;
            for (java.util.Map.Entry<Float, Integer> e : sizeCounts.entrySet()) {
                if (e.getValue() > max) {
                    max = e.getValue();
                    best = e.getKey();
                }
            }
            return best;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions)
                throws IOException {
            for (TextPosition tp : positions) {
                String name = tp.getFont().getName().toLowerCase();
                String family;
                if (name.contains("times") || name.contains("cambria")) family = "Times";
                else if (name.contains("courier")) family = "Courier";
                else if (name.contains("arial") || name.contains("helvetica")
                        || name.contains("calibri")) family = "Helvetica";
                else family = "Times"; // default (CIDFont, etc.)

                familyCounts.merge(family, 1, Integer::sum);

                // Sanity: getFontSizeInPt() puede devolver valores inflados (45-83pt)
                // en PDFs con espacio de coordenadas escalado. Usar getHeight() como
                // estimación visual cuando el valor reportado es excesivo.
                float sz = tp.getFontSizeInPt();
                if (sz > 30f) {
                    float h = tp.getHeight();
                    sz = (h > 0 && h < 30f) ? h : 11f;
                }
                sz = Math.round(sz * 10f) / 10f;
                sizeCounts.merge(sz, 1, Integer::sum);
            }
            super.writeString(text, positions);
        }
    }
}
