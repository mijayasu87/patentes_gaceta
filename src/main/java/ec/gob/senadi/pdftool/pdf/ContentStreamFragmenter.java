package ec.gob.senadi.pdftool.pdf;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Fragmenta el content stream de una página PDF filtrando por rango Y.
 *
 * Parsea los operadores PDF a nivel de content stream:
 *   - BT...ET (bloques de texto): filtra por posición Y de cada Tj/TJ
 *   - Operadores de estado (q, Q, cm, colores, fuentes): se preservan siempre
 *   - No re-renderiza ni cambia fuentes: copia los operadores originales tal cual
 *
 * Resultado: PDF vectorial, texto seleccionable, sin contenido fantasma.
 */
public class ContentStreamFragmenter {

    private static final Logger LOG = Logger.getLogger(ContentStreamFragmenter.class.getName());

    /** Resultado de la extracción de un fragmento. */
    public static class FragmentResult {
        public final List<Object> tokens;
        public final Set<COSName> usedFontNames;
        /** Y mínimo (en coords fuente) donde hay contenido real (texto o Do). */
        public final float minContentY;
        /** Y máximo (en coords fuente) donde hay contenido real (texto o Do). */
        public final float maxContentY;

        FragmentResult(List<Object> tokens, Set<COSName> usedFontNames,
                       float minContentY, float maxContentY) {
            this.tokens = tokens;
            this.usedFontNames = usedFontNames;
            this.minContentY = minContentY;
            this.maxContentY = maxContentY;
        }

        public boolean hasContent() {
            for (Object t : tokens) {
                if (t instanceof Operator) {
                    String n = ((Operator) t).getName();
                    if ("Tj".equals(n) || "TJ".equals(n)
                            || "'".equals(n) || "\"".equals(n)
                            || "Do".equals(n)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Copia TODOS los recursos de la página fuente a la página destino:
     * Fonts, XObjects (imágenes, fórmulas), ExtGState, ColorSpace, Pattern, Shading.
     *
     * Opera a nivel de diccionario COS: para cada categoría de recurso,
     * fusiona las claves del src en el dst.
     *
     * Si un nombre de recurso ya existe en el destino con un objeto DIFERENTE,
     * lo renombra (prefijo _S#_) y devuelve el mapeo para que el content stream
     * pueda actualizarse. Esto evita que las fuentes originales de las
     * reivindicaciones colisionen con las fuentes de OpenPDF en la primera página.
     *
     * @return mapeo oldName → newName para recursos renombrados (vacío si no hubo conflictos)
     */
    public static Map<COSName, COSName> copyAllResources(PDPage srcPage, PDPage dstPage) {
        Map<COSName, COSName> nameRemap = new HashMap<>();

        PDResources srcRes = srcPage.getResources();
        if (srcRes == null) return nameRemap;

        PDResources dstRes = dstPage.getResources();
        if (dstRes == null) {
            dstRes = new PDResources();
            dstPage.setResources(dstRes);
        }

        COSDictionary srcDict = srcRes.getCOSObject();
        COSDictionary dstDict = dstRes.getCOSObject();

        COSName[] categories = {
            COSName.FONT, COSName.XOBJECT, COSName.EXT_G_STATE,
            COSName.getPDFName("ColorSpace"), COSName.PATTERN, COSName.SHADING,
            COSName.getPDFName("Properties")
        };

        for (COSName category : categories) {
            COSBase srcCatBase = srcDict.getDictionaryObject(category);
            if (!(srcCatBase instanceof COSDictionary)) continue;
            COSDictionary srcCat = (COSDictionary) srcCatBase;

            COSBase dstCatBase = dstDict.getDictionaryObject(category);
            COSDictionary dstCat;
            if (dstCatBase instanceof COSDictionary) {
                dstCat = (COSDictionary) dstCatBase;
            } else {
                dstCat = new COSDictionary();
                dstDict.setItem(category, dstCat);
            }

            for (COSName key : srcCat.keySet()) {
                COSBase srcItem = srcCat.getItem(key);
                COSBase dstItem = dstCat.getItem(key);

                if (dstItem == null) {
                    // Sin conflicto: copiar tal cual
                    dstCat.setItem(key, srcItem);
                } else if (dstItem == srcItem) {
                    // Mismo objeto: ya está
                } else {
                    // Conflicto: distinto objeto con el mismo nombre.
                    // Generar nombre único y registrar el remapeo.
                    COSName newKey = key;
                    int counter = 1;
                    do {
                        newKey = COSName.getPDFName("_S" + counter + "_" + key.getName());
                        counter++;
                    } while (dstCat.containsKey(newKey));

                    dstCat.setItem(newKey, srcItem);
                    nameRemap.put(key, newKey);
                    LOG.fine("copyAllResources: conflicto " + category.getName()
                            + "/" + key.getName() + " → renombrado a " + newKey.getName());
                }
            }
        }

        return nameRemap;
    }

    /**
     * Aplica un remapeo de nombres de recursos a los tokens del content stream.
     * Cubre los operadores que referencian recursos por nombre:
     *   - Tf (fuentes): /FontName size Tf
     *   - Do (XObjects): /XObjName Do
     *   - gs (ExtGState): /GStateName gs
     *   - cs/CS (ColorSpace): /CSName cs
     *   - sh (Shading): /ShadingName sh
     *   - scn/SCN (Patterns): si el último operando es COSName
     */
    public static void remapResourceNames(
            List<Object> tokens, Map<COSName, COSName> nameRemap) {
        if (nameRemap.isEmpty()) return;

        for (int i = 0; i < tokens.size(); i++) {
            Object t = tokens.get(i);
            if (!(t instanceof Operator)) continue;

            String name = ((Operator) t).getName();

            // Tf: /FontName size Tf → COSName está en i-2
            if ("Tf".equals(name) && i >= 2
                    && tokens.get(i - 2) instanceof COSName) {
                COSName old = (COSName) tokens.get(i - 2);
                COSName mapped = nameRemap.get(old);
                if (mapped != null) tokens.set(i - 2, mapped);
            }
            // Do: /XObjName Do → COSName está en i-1
            else if ("Do".equals(name) && i >= 1
                    && tokens.get(i - 1) instanceof COSName) {
                COSName old = (COSName) tokens.get(i - 1);
                COSName mapped = nameRemap.get(old);
                if (mapped != null) tokens.set(i - 1, mapped);
            }
            // gs: /GStateName gs → COSName está en i-1
            else if ("gs".equals(name) && i >= 1
                    && tokens.get(i - 1) instanceof COSName) {
                COSName old = (COSName) tokens.get(i - 1);
                COSName mapped = nameRemap.get(old);
                if (mapped != null) tokens.set(i - 1, mapped);
            }
            // cs/CS: /CSName cs → COSName está en i-1
            else if (("cs".equals(name) || "CS".equals(name)) && i >= 1
                    && tokens.get(i - 1) instanceof COSName) {
                COSName old = (COSName) tokens.get(i - 1);
                COSName mapped = nameRemap.get(old);
                if (mapped != null) tokens.set(i - 1, mapped);
            }
            // sh: /ShadingName sh → COSName está en i-1
            else if ("sh".equals(name) && i >= 1
                    && tokens.get(i - 1) instanceof COSName) {
                COSName old = (COSName) tokens.get(i - 1);
                COSName mapped = nameRemap.get(old);
                if (mapped != null) tokens.set(i - 1, mapped);
            }
        }
    }

    /**
     * Calcula el rango Y real ocupado por un XObject ya posicionado por el CTM.
     *
     * Para PDImageXObject (raster), el espacio local es [0,0,1,1] → alto = |ctmD|.
     * Para PDFormXObject (fórmulas químicas/matemáticas tipo ChemDraw/MathJax),
     * el espacio local lo define el BBox del Form, que puede ser arbitrario.
     *
     * Sin esta corrección, el rango calculado solo refleja el factor de escala
     * y no el alto real del contenido del Form (causa de fórmulas recortadas).
     */
    private static float[] xobjectYRange(
            PDPage page, COSName xobjName,
            float ctmD, float ctmF) {

        float bboxBottom = 0f;
        float bboxTop    = 1f;

        if (xobjName != null && page.getResources() != null) {
            try {
                PDXObject xobj = page.getResources().getXObject(xobjName);
                if (xobj instanceof PDFormXObject) {
                    PDRectangle bbox = ((PDFormXObject) xobj).getBBox();
                    if (bbox != null) {
                        bboxBottom = bbox.getLowerLeftY();
                        bboxTop    = bbox.getUpperRightY();
                    }
                }
                // Para PDImageXObject: dimensiones intrínsecas normalizadas → [0,1]
            } catch (IOException ignored) {
                // Si no se puede leer el XObject, asumir espacio unitario
            }
        }

        float y0 = ctmF + bboxBottom * ctmD;
        float y1 = ctmF + bboxTop    * ctmD;
        return new float[]{ Math.min(y0, y1), Math.max(y0, y1) };
    }

    /**
     * Calcula el rango Y efectivo (en coordenadas de página) que aporta un operador
     * de path-building. Devuelve {@code [minY, maxY]} o {@code null} si el operador
     * no contribuye Y (como {@code h}).
     *
     * Aplica el componente Y del CTM actual: {@code effY = ctmD * rawY + ctmF}.
     */
    private static float[] pathOpYRange(
            String name, List<Object> operands, float ctmD, float ctmF) {

        if ("m".equals(name) || "l".equals(name)) {
            if (operands.size() >= 2) {
                float y = ctmD * toFloat(operands.get(operands.size() - 1)) + ctmF;
                return new float[]{ y, y };
            }
        } else if ("c".equals(name) && operands.size() >= 6) {
            float y1 = ctmD * toFloat(operands.get(operands.size() - 5)) + ctmF;
            float y2 = ctmD * toFloat(operands.get(operands.size() - 3)) + ctmF;
            float y3 = ctmD * toFloat(operands.get(operands.size() - 1)) + ctmF;
            float lo = Math.min(y1, Math.min(y2, y3));
            float hi = Math.max(y1, Math.max(y2, y3));
            return new float[]{ lo, hi };
        } else if (("v".equals(name) || "y".equals(name)) && operands.size() >= 4) {
            float y2 = ctmD * toFloat(operands.get(operands.size() - 3)) + ctmF;
            float y3 = ctmD * toFloat(operands.get(operands.size() - 1)) + ctmF;
            return new float[]{ Math.min(y2, y3), Math.max(y2, y3) };
        } else if ("re".equals(name) && operands.size() >= 4) {
            float y = toFloat(operands.get(operands.size() - 3));
            float h = toFloat(operands.get(operands.size() - 1));
            float effY1 = ctmD * y + ctmF;
            float effY2 = ctmD * (y + h) + ctmF;
            return new float[]{ Math.min(effY1, effY2), Math.max(effY1, effY2) };
        }
        // h no aporta nuevos vértices (solo cierra)
        return null;
    }

    /**
     * Devuelve el último COSName en la lista (típicamente el nombre del XObject
     * referenciado por el operador siguiente como /XObjName Do).
     */
    private static COSName lastCOSName(List<Object> tokens) {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (tokens.get(i) instanceof COSName) return (COSName) tokens.get(i);
        }
        return null;
    }

    /**
     * Detecta los rangos Y ocupados por XObjects (operadores {@code Do}) en una página,
     * filtrando los que se solapan con {@code [yMin, yMax]}.
     *
     * Útil para evitar que el fragmentador corte una fórmula/imagen a la mitad
     * cuando reparte el rango Y en sub-fragmentos de páginas distintas.
     *
     * @return lista de {@code [imgYBottom, imgYTop]} (coords PDF) por cada XObject relevante,
     *         ordenada de mayor Y a menor Y (top → bottom).
     */
    public static List<float[]> extractXObjectYRanges(
            PDPage page, float yMin, float yMax) throws IOException {

        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();
        List<Object> allTokens = parser.getTokens();

        List<float[]> ranges = new ArrayList<>();
        float ctmA = 1f, ctmD = 1f, ctmE = 0f, ctmF = 0f;
        java.util.ArrayDeque<float[]> ctmStack = new java.util.ArrayDeque<>();
        List<Object> pendingOperands = new ArrayList<>();

        for (int i = 0; i < allTokens.size(); i++) {
            Object token = allTokens.get(i);
            if (!(token instanceof Operator)) {
                pendingOperands.add(token);
                continue;
            }
            String name = ((Operator) token).getName();

            if ("q".equals(name)) {
                ctmStack.push(new float[]{ctmA, ctmD, ctmE, ctmF});
            } else if ("Q".equals(name)) {
                if (!ctmStack.isEmpty()) {
                    float[] prev = ctmStack.pop();
                    ctmA = prev[0]; ctmD = prev[1]; ctmE = prev[2]; ctmF = prev[3];
                }
            } else if ("cm".equals(name) && pendingOperands.size() >= 6) {
                float cmA = toFloat(pendingOperands.get(pendingOperands.size() - 6));
                float cmD = toFloat(pendingOperands.get(pendingOperands.size() - 3));
                float cmE = toFloat(pendingOperands.get(pendingOperands.size() - 2));
                float cmF = toFloat(pendingOperands.get(pendingOperands.size() - 1));
                float newA = ctmA * cmA;
                float newE = ctmA * cmE + ctmE;
                float newD = ctmD * cmD;
                float newF = ctmD * cmF + ctmF;
                ctmA = newA; ctmD = newD; ctmE = newE; ctmF = newF;
            } else if ("Do".equals(name)) {
                // Rango Y real considerando el BBox del Form XObject (si aplica).
                COSName xobjName = lastCOSName(pendingOperands);
                float[] yr = xobjectYRange(page, xobjName, ctmD, ctmF);
                if (yr[1] > yMin && yr[0] < yMax) {
                    ranges.add(yr);
                    LOG.info("extractXObjectYRanges: Do " + xobjName + " Y=["
                           + yr[0] + ", " + yr[1] + "] alto=" + (yr[1] - yr[0])
                           + " (ctmF=" + ctmF + " ctmD=" + ctmD + ")");
                }
            }
            pendingOperands.clear();
        }

        // Ordenar de mayor Y a menor Y (top → bottom)
        ranges.sort((a, b) -> Float.compare(b[1], a[1]));
        return ranges;
    }

    /**
     * Extrae del content stream de una página solo el contenido cuyo
     * posición Y cae dentro del rango [yBottom, yTop] (coordenadas PDF, Y desde abajo).
     *
     * Filtra:
     *   - Bloques BT...ET: por posición Y del cursor de texto (Tm, Td)
     *   - Operadores Do (XObjects: imágenes, fórmulas): por posición Y de la CTM
     *
     * Preserva:
     *   - Operadores de estado gráfico (q, Q, cm, colores, etc.)
     *   - Operadores de posicionamiento dentro de BT...ET (para mantener cursor)
     *
     * Resultado: contenido vectorial, sin fantasmas gráficos fuera de rango.
     */
    public static FragmentResult extractByYRange(
            PDPage page, float yBottom, float yTop) throws IOException {

        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();
        List<Object> allTokens = parser.getTokens();

        List<Object> result = new ArrayList<>();
        Set<COSName> usedFonts = new HashSet<>();
        float minContentY = yTop;
        float maxContentY = yBottom;

        // Dimensiones de la página para detectar fondos de página completa
        float pageW = page.getMediaBox().getWidth();
        float pageH = page.getMediaBox().getHeight();
        float pageArea = pageW * pageH;

        // Rastrear CTM para transformar coordenadas de texto y Do.
        // Sin rotación: effectiveY = ctmD * rawY + ctmF, effectiveX = ctmA * rawX + ctmE
        float ctmA = 1f, ctmD = 1f, ctmE = 0f, ctmF = 0f;
        java.util.ArrayDeque<float[]> ctmStack = new java.util.ArrayDeque<>();

        // Buffer de operandos para contenido fuera de BT...ET
        List<Object> pendingOperands = new ArrayList<>();

        // Rastrear el área máxima de rectángulos en el path actual
        float maxRectArea = 0f;
        boolean hasRect = false;

        // ── Tracking del path actual para filtrar paths fuera del rango Y ──
        // (líneas/strokes/rectángulos en headers/footers del PDF fuente)
        List<Object> pendingPath = new ArrayList<>();
        float pathMinY = Float.POSITIVE_INFINITY;
        float pathMaxY = Float.NEGATIVE_INFINITY;

        int i = 0;
        while (i < allTokens.size()) {
            Object token = allTokens.get(i);

            // Acumular operandos (no son operadores)
            if (!(token instanceof Operator)) {
                pendingOperands.add(token);
                i++;
                continue;
            }

            Operator op = (Operator) token;
            String name = op.getName();

            if ("BT".equals(name)) {
                // ── Bloque de texto: filtrar por Y ──
                int etIdx = findET(allTokens, i);
                if (etIdx < 0) { pendingOperands.clear(); i++; continue; }

                List<Object> blockTokens = new ArrayList<>(
                        allTokens.subList(i, etIdx + 1));
                FilteredBlock fb = filterTextBlock(blockTokens, yBottom, yTop, ctmA, ctmD, ctmE, ctmF);

                if (fb.hasVisibleText) {
                    result.addAll(fb.tokens);
                    usedFonts.addAll(fb.usedFonts);
                    if (fb.minTextY < minContentY) {
                        minContentY = fb.minTextY;
                    }
                    if (fb.maxTextY > maxContentY) {
                        maxContentY = fb.maxTextY;
                    }
                } else if (!fb.usedFonts.isEmpty()) {
                    // Bloque sin texto visible en rango pero con Tf (cambio de fuente).
                    // Incluir para preservar el estado de fuente que bloques
                    // posteriores heredan (el font persiste entre BT..ET).
                    result.addAll(fb.tokens);
                    usedFonts.addAll(fb.usedFonts);
                }

                pendingOperands.clear();
                i = etIdx + 1;
            } else {
                // ── Contenido fuera de bloques de texto ──

                // Rastrear estado gráfico (CTM)
                if ("q".equals(name)) {
                    ctmStack.push(new float[]{ctmA, ctmD, ctmE, ctmF});
                } else if ("Q".equals(name)) {
                    if (!ctmStack.isEmpty()) {
                        float[] prev = ctmStack.pop();
                        ctmA = prev[0]; ctmD = prev[1]; ctmE = prev[2]; ctmF = prev[3];
                    }
                } else if ("cm".equals(name) && pendingOperands.size() >= 6) {
                    // Nuevo CTM = viejo CTM × cm. Sin rotación:
                    // newA = ctmA * cmA, newE = ctmA * cmE + ctmE
                    // newD = ctmD * cmD, newF = ctmD * cmF + ctmF
                    float cmA = toFloat(pendingOperands.get(pendingOperands.size() - 6));
                    float cmD = toFloat(pendingOperands.get(pendingOperands.size() - 3));
                    float cmE = toFloat(pendingOperands.get(pendingOperands.size() - 2));
                    float cmF = toFloat(pendingOperands.get(pendingOperands.size() - 1));
                    float newA = ctmA * cmA;
                    float newE = ctmA * cmE + ctmE;
                    float newD = ctmD * cmD;
                    float newF = ctmD * cmF + ctmF;
                    ctmA = newA; ctmD = newD; ctmE = newE; ctmF = newF;
                }

                // ── Path-building: acumular en pendingPath con tracking de Y ──
                boolean isPathBuild = "m".equals(name) || "l".equals(name)
                        || "c".equals(name) || "v".equals(name) || "y".equals(name)
                        || "h".equals(name) || "re".equals(name);
                if (isPathBuild) {
                    float[] yRange = pathOpYRange(name, pendingOperands, ctmD, ctmF);
                    if (yRange != null) {
                        if (yRange[0] < pathMinY) pathMinY = yRange[0];
                        if (yRange[1] > pathMaxY) pathMaxY = yRange[1];
                    }
                    if ("re".equals(name) && pendingOperands.size() >= 4) {
                        float w = Math.abs(toFloat(pendingOperands.get(pendingOperands.size() - 2)));
                        float h = Math.abs(toFloat(pendingOperands.get(pendingOperands.size() - 1)));
                        float area = w * h;
                        if (area > maxRectArea) maxRectArea = area;
                        hasRect = true;
                    }
                    pendingPath.addAll(pendingOperands);
                    pendingPath.add(token);
                    pendingOperands.clear();
                    i++;
                    continue;
                }

                // ── Paint y clipping ──
                boolean isFill   = "f".equals(name) || "f*".equals(name) || "F".equals(name);
                boolean isStroke = "S".equals(name) || "s".equals(name);
                boolean isBoth   = "b".equals(name) || "b*".equals(name)
                                  || "B".equals(name) || "B*".equals(name);
                boolean isEnd    = "n".equals(name);
                boolean isClip   = "W".equals(name) || "W*".equals(name);

                if (isFill || isStroke || isBoth || isEnd || isClip) {
                    boolean isFullPage = hasRect && pageArea > 0
                                       && (maxRectArea / pageArea) > 0.5f;
                    boolean entirelyOutside = pathMaxY < yBottom || pathMinY > yTop;

                    if (isClip) {
                        // Clipping path: emitir siempre (un clip fuera de rango
                        // simplemente recorta más, nunca añade contenido visible).
                        result.addAll(pendingPath);
                        result.addAll(pendingOperands);
                        result.add(token);
                    } else if (isEnd) {
                        // n = terminar path sin pintar: descartar el path acumulado
                        result.add(token);
                    } else if (isFullPage && isFill) {
                        // Fondo blanco de página completa: reemplazar por 'n'
                        result.add(Operator.getOperator("n"));
                    } else if (isFullPage && isBoth) {
                        // fill+stroke de página completa: dejar solo stroke
                        String strokeOnly = ("b".equals(name) || "b*".equals(name)) ? "s" : "S";
                        result.addAll(pendingPath);
                        result.add(Operator.getOperator(strokeOnly));
                    } else if (entirelyOutside) {
                        // Path completamente fuera del rango Y: descartar
                        // (típicamente líneas/strokes del header/footer del fuente)
                    } else {
                        // Path en rango: emitir tal cual
                        result.addAll(pendingPath);
                        result.addAll(pendingOperands);
                        result.add(token);
                    }

                    pendingPath.clear();
                    pendingOperands.clear();
                    pathMinY = Float.POSITIVE_INFINITY;
                    pathMaxY = Float.NEGATIVE_INFINITY;
                    hasRect = false;
                    maxRectArea = 0f;
                    i++;
                    continue;
                }

                if ("Do".equals(name)) {
                    // Rango Y real considerando el BBox del Form XObject (si aplica).
                    // Sin BBox correcto, fórmulas químicas (Form XObject con BBox no-unitario)
                    // se calculaban como de altura |ctmD| pt y el clip recortaba el cuerpo.
                    COSName xobjName = lastCOSName(pendingOperands);
                    float[] yr = xobjectYRange(page, xobjName, ctmD, ctmF);
                    float imgYBottom = yr[0];
                    float imgYTop    = yr[1];
                    boolean inRange = imgYTop > yBottom && imgYBottom < yTop;
                    if (inRange) {
                        result.addAll(pendingOperands);
                        result.add(token);
                        if (imgYBottom < minContentY) minContentY = imgYBottom;
                        if (imgYTop    > maxContentY) maxContentY = imgYTop;
                    }
                } else {
                    // Operadores de estado: preservar siempre
                    result.addAll(pendingOperands);
                    result.add(token);
                }

                pendingOperands.clear();
                i++;
            }
        }

        return new FragmentResult(result, usedFonts, minContentY, maxContentY);
    }

    /**
     * Copia las fuentes referenciadas de la página fuente a la página destino.
     * Si hay conflicto de nombres (misma clave, distinto font), renombra.
     *
     * @return mapeo srcName → dstName (identidad si no hubo conflicto)
     */
    public static Map<COSName, COSName> copyFonts(
            PDPage srcPage, PDPage dstPage,
            Set<COSName> fontNames) throws IOException {

        PDResources srcRes = srcPage.getResources();
        PDResources dstRes = dstPage.getResources();
        if (dstRes == null) {
            dstRes = new PDResources();
            dstPage.setResources(dstRes);
        }

        Map<COSName, COSName> nameMap = new HashMap<>();

        for (COSName srcName : fontNames) {
            if (srcRes == null) continue;
            PDFont srcFont;
            try {
                srcFont = srcRes.getFont(srcName);
            } catch (Exception e) {
                continue;
            }
            if (srcFont == null) continue;

            COSName dstName = srcName;
            try {
                PDFont existing = dstRes.getFont(dstName);
                if (existing != null
                        && existing.getCOSObject() != srcFont.getCOSObject()) {
                    // Conflicto: buscar nombre libre
                    int c = 1;
                    do {
                        dstName = COSName.getPDFName("CF" + c++);
                    } while (dstRes.getFont(dstName) != null);
                }
            } catch (IOException ignored) { }

            dstRes.put(dstName, srcFont);
            nameMap.put(srcName, dstName);
        }

        return nameMap;
    }

    /**
     * Renombra las referencias a fuentes en los tokens según el mapeo.
     * Solo afecta operadores Tf: /FontName size Tf
     */
    public static void remapFontNames(
            List<Object> tokens, Map<COSName, COSName> nameMap) {
        if (nameMap.isEmpty()) return;

        for (int i = 0; i < tokens.size(); i++) {
            Object t = tokens.get(i);
            if (t instanceof Operator
                    && "Tf".equals(((Operator) t).getName())) {
                // Tf tiene 2 operandos: /FontName size
                if (i >= 2 && tokens.get(i - 2) instanceof COSName) {
                    COSName old = (COSName) tokens.get(i - 2);
                    COSName mapped = nameMap.get(old);
                    if (mapped != null && !mapped.equals(old)) {
                        tokens.set(i - 2, mapped);
                    }
                }
            }
        }
    }

    /**
     * Escribe los tokens como un nuevo content stream appended a la página,
     * envuelto en q/Q con traslación Y vía operador cm.
     * Sin clipping (compatibilidad).
     */
    public static void writeToPage(
            PDDocument doc, PDPage page,
            List<Object> tokens, float offsetY) throws IOException {
        writeToPage(doc, page, tokens, offsetY, -1, -1);
    }

    /**
     * Escribe los tokens como un nuevo content stream appended a la página,
     * envuelto en q/Q con traslación Y vía operador cm.
     *
     * Si clipBottom/clipTop son válidos (>= 0), agrega un rectángulo de clipping
     * antes del cm. Esto impide que fondos blancos del PDF fuente tapen el
     * contenido existente en la página destino.
     */
    public static void writeToPage(
            PDDocument doc, PDPage page,
            List<Object> tokens, float offsetY,
            float clipBottom, float clipTop) throws IOException {

        List<Object> wrapped = new ArrayList<>();

        // q — guardar estado gráfico
        wrapped.add(Operator.getOperator("q"));

        // ── Clipping rectangle (si se especifica) ──
        if (clipBottom >= 0 && clipTop > clipBottom) {
            float clipX = 0;
            float clipW = page.getMediaBox().getWidth();
            float clipH = clipTop - clipBottom;
            wrapped.add(new COSFloat(clipX));
            wrapped.add(new COSFloat(clipBottom));
            wrapped.add(new COSFloat(clipW));
            wrapped.add(new COSFloat(clipH));
            wrapped.add(Operator.getOperator("re"));
            wrapped.add(Operator.getOperator("W"));
            wrapped.add(Operator.getOperator("n"));
        }

        // 1 0 0 1 0 offsetY cm — traslación vertical
        wrapped.add(new COSFloat(1));
        wrapped.add(COSInteger.ZERO);
        wrapped.add(COSInteger.ZERO);
        wrapped.add(new COSFloat(1));
        wrapped.add(COSInteger.ZERO);
        wrapped.add(new COSFloat(offsetY));
        wrapped.add(Operator.getOperator("cm"));

        // Contenido filtrado
        wrapped.addAll(tokens);

        // Q — restaurar estado gráfico
        wrapped.add(Operator.getOperator("Q"));

        // Crear stream y escribir tokens
        PDStream stream = new PDStream(doc);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter writer = new ContentStreamWriter(out);
            writer.writeTokens(wrapped);
        }

        // Añadir al array de content streams de la página (append, no reemplazar)
        COSBase existing = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        COSArray arr;
        if (existing instanceof COSArray) {
            arr = (COSArray) existing;
        } else {
            arr = new COSArray();
            if (existing != null) {
                arr.add(existing);
            }
        }
        arr.add(stream);
        page.getCOSObject().setItem(COSName.CONTENTS, arr);
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Internos ─────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════

    private static class FilteredBlock {
        List<Object> tokens = new ArrayList<>();
        boolean hasVisibleText;
        Set<COSName> usedFonts = new HashSet<>();
        float minTextY = Float.MAX_VALUE; // Y más bajo de texto visible
        float maxTextY = -Float.MAX_VALUE; // Y más alto de texto visible
    }

    /**
     * Filtra un bloque BT...ET conservando solo las operaciones de mostrar texto
     * cuya posición Y caiga dentro de [yBottom, yTop] en coordenadas de página.
     *
     * @param ctmA componente a de la CTM actual (escalado X)
     * @param ctmD componente d de la CTM actual (escalado Y, negativo si Y invertido)
     * @param ctmE componente e de la CTM actual (traslación X)
     * @param ctmF componente f de la CTM actual (traslación Y)
     * effectiveY = ctmD * rawTmY + ctmF, effectiveX = ctmA * rawTmX + ctmE
     *
     * - Operadores de posicionamiento (Tm, Td, TD, T*) se preservan siempre
     *   para mantener el cursor correcto para las líneas que sí están en rango.
     * - Operadores de estado (Tf, Tc, Tw, Tr, TL, Ts) se preservan siempre.
     * - Operadores de mostrar texto (Tj, TJ) se incluyen SOLO si Y está en rango.
     * - ' (newline+show): si fuera de rango → se reemplaza por T* (solo cursor).
     * - " (spacing+newline+show): si fuera de rango → se reemplaza por Tw+Tc+T*.
     */
    private static FilteredBlock filterTextBlock(
            List<Object> block, float yBottom, float yTop,
            float ctmA, float ctmD, float ctmE, float ctmF) {

        FilteredBlock fb = new FilteredBlock();

        float textY = 0, lineY = 0, leading = 0;
        // baselineY: posición Y de la línea principal. No se altera por
        // desplazamientos pequeños (subíndices/superíndices de fórmulas químicas).
        // Se usa para el chequeo de rango en vez de textY.
        float baselineY = 0;
        float textX = 0; // Posición X para detectar números de línea del margen
        // Text matrix Y-scale (componente d). Cuando el PDF usa Tm con flip
        // vertical (1 0 0 -1 e f), tlmD=-1 y los desplazamientos Td/TD/T*
        // se invierten en su sentido respecto al espacio del CTM externo.
        // Sin este tracking, Google Docs (que usa este patrón) hacía que
        // mi cursor Y quedara en un valor sin relación con la posición real.
        float tlmD = 1f;
        // Umbral: desplazamientos Y menores a esto se consideran sub/superíndice
        float SUB_THRESHOLD = 8f;
        // Números de línea del margen izquierdo están a X < 80
        float MARGIN_X = 80f;
        List<Object> operands = new ArrayList<>();

        for (Object token : block) {
            if (!(token instanceof Operator)) {
                operands.add(token);
                continue;
            }

            Operator op = (Operator) token;
            String name = op.getName();

            // ── Actualizar posición Y del cursor de texto ──
            switch (name) {
                case "Tm":
                    if (operands.size() >= 6) {
                        tlmD  = toFloat(operands.get(operands.size() - 3)); // d
                        textY = toFloat(operands.get(operands.size() - 1)); // f
                        textX = toFloat(operands.get(operands.size() - 2)); // e
                        lineY = textY;
                        baselineY = textY; // Posicionamiento absoluto = nueva baseline
                    }
                    break;
                case "Td":
                    if (operands.size() >= 2) {
                        float ty = toFloat(operands.get(operands.size() - 1));
                        float adj = tlmD * ty;
                        textX += toFloat(operands.get(operands.size() - 2));
                        lineY += adj;
                        textY = lineY;
                        if (Math.abs(adj) >= SUB_THRESHOLD) {
                            baselineY = lineY; // Salto grande = nueva línea
                        }
                        // Salto pequeño = subíndice/superíndice, baselineY no cambia
                    }
                    break;
                case "TD":
                    if (operands.size() >= 2) {
                        float ty = toFloat(operands.get(operands.size() - 1));
                        float adj = tlmD * ty;
                        textX += toFloat(operands.get(operands.size() - 2));
                        lineY += adj;
                        textY = lineY;
                        leading = -ty;
                        if (Math.abs(adj) >= SUB_THRESHOLD) {
                            baselineY = lineY;
                        }
                    }
                    break;
                case "TL":
                    if (operands.size() >= 1) {
                        leading = toFloat(operands.get(operands.size() - 1));
                    }
                    break;
                case "T*":
                    lineY -= leading * tlmD;
                    textY = lineY;
                    baselineY = lineY; // T* = salto de línea explícito
                    break;
            }

            // ── Rastrear fuentes usadas ──
            if ("Tf".equals(name) && operands.size() >= 2
                    && operands.get(operands.size() - 2) instanceof COSName) {
                fb.usedFonts.add((COSName) operands.get(operands.size() - 2));
            }

            // ── ¿Es operador de mostrar texto? ──
            boolean isShow = "Tj".equals(name) || "TJ".equals(name)
                    || "'".equals(name) || "\"".equals(name);

            // Actualizar cursor para operadores compuestos (antes de decidir)
            if ("'".equals(name)) {
                lineY -= leading * tlmD;
                textY = lineY;
                baselineY = lineY; // ' = nueva línea + mostrar
            } else if ("\"".equals(name)) {
                lineY -= leading * tlmD;
                textY = lineY;
                baselineY = lineY; // " = nueva línea + mostrar
            }

            if (isShow) {
                // Convertir baselineY (raw Tm Y) a coordenadas de página
                // usando la CTM: effectiveY = ctmD * rawY + ctmF
                float effectiveY = ctmD * baselineY + ctmF;

                // Usar effectiveY para el chequeo de rango: los subíndices/superíndices
                // (desplazamientos Y < 8pt) se agrupan con su línea principal y nunca
                // se separan a otra página.
                boolean inRange = effectiveY > yBottom && effectiveY <= yTop;

                // Transformar textX usando CTM para posición real en página
                float effectiveX = ctmA * textX + ctmE;

                if (inRange) {
                    // Los números de página los gestiona el detector de header/footer
                    // a nivel macro (detectHeaderFooterBands). Aquí no descartamos
                    // texto numérico para no perder subíndices como "CF3", "R12".
                    fb.hasVisibleText = true;
                    // Solo contar texto del área principal para bounds
                    // (excluir números de línea del margen izquierdo X < 80)
                    if (effectiveX >= MARGIN_X) {
                        if (effectiveY < fb.minTextY) fb.minTextY = effectiveY;
                        if (effectiveY > fb.maxTextY) fb.maxTextY = effectiveY;
                    }
                    fb.tokens.addAll(operands);
                    fb.tokens.add(token);
                } else {
                    // Fuera de rango: no mostrar texto pero mantener cursor
                    if ("'".equals(name)) {
                        // ' = T* + Tj → emitir solo T*
                        fb.tokens.add(Operator.getOperator("T*"));
                    } else if ("\"".equals(name) && operands.size() >= 3) {
                        // " = Tw + Tc + T* + Tj
                        fb.tokens.add(operands.get(0)); // aw
                        fb.tokens.add(Operator.getOperator("Tw"));
                        fb.tokens.add(operands.get(1)); // ac
                        fb.tokens.add(Operator.getOperator("Tc"));
                        fb.tokens.add(Operator.getOperator("T*"));
                    }
                    // Para Tj/TJ: simplemente no emitir nada (el cursor ya fue
                    // actualizado por el Td/Tm ANTERIOR al Tj)
                }
            } else {
                // No es show-text: incluir siempre (posicionamiento, estado, etc.)
                fb.tokens.addAll(operands);
                fb.tokens.add(token);
            }

            operands.clear();
        }

        return fb;
    }

    /** Encuentra el índice del operador ET que cierra el BT en btIdx. */
    private static int findET(List<Object> tokens, int btIdx) {
        for (int i = btIdx + 1; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof Operator
                    && "ET".equals(((Operator) tokens.get(i)).getName())) {
                return i;
            }
        }
        return -1;
    }

    /** Convierte un COSNumber a float, 0 si no es número. */
    private static float toFloat(Object o) {
        return (o instanceof COSNumber) ? ((COSNumber) o).floatValue() : 0f;
    }

    /**
     * Detecta números de línea del margen izquierdo: dígitos aislados
     * múltiplos de 5 (5, 10, 15, 20, 25, 30, 35) posicionados a X &lt; 55pt.
     * Patrón típico de documentos de patentes con numeración de líneas.
     */
    private static boolean isMarginLineNumber(List<Object> operands, String opName, float textX) {
        if (textX >= 55f) return false;
        String text = extractShowText(operands, opName);
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        if (!trimmed.matches("\\d{1,3}")) return false;
        try {
            int val = Integer.parseInt(trimmed);
            return val > 0 && val <= 50 && val % 5 == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Extrae el texto visible de los operandos de Tj / TJ / ' / ".
     */
    private static String extractShowText(List<Object> operands, String opName) {
        if (operands.isEmpty()) return null;

        if ("Tj".equals(opName) || "'".equals(opName)) {
            Object arg = operands.get(operands.size() - 1);
            if (arg instanceof COSString) {
                return ((COSString) arg).getString();
            }
        } else if ("\"".equals(opName)) {
            if (operands.size() >= 3) {
                Object arg = operands.get(operands.size() - 1);
                if (arg instanceof COSString) {
                    return ((COSString) arg).getString();
                }
            }
        } else if ("TJ".equals(opName)) {
            Object arg = operands.get(operands.size() - 1);
            if (arg instanceof COSArray) {
                StringBuilder sb = new StringBuilder();
                for (COSBase item : (COSArray) arg) {
                    if (item instanceof COSString) {
                        sb.append(((COSString) item).getString());
                    }
                }
                return sb.toString();
            }
        }
        return null;
    }
}
