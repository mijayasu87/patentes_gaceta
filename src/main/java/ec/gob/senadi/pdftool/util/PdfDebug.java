package ec.gob.senadi.pdftool.util;

import java.io.File;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * Volcado del content stream para depuración.
 * Uso: java -cp ... ec.gob.senadi.pdftool.util.PdfDebug archivo.pdf [pageIdx]
 */
public class PdfDebug {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.out.println("Usage: PdfDebug <file> [page]"); return; }
        File f = new File(args[0]);
        int pageIdx = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        try (PDDocument doc = PDDocument.load(f)) {
            PDPage page = doc.getPage(pageIdx);
            PDFStreamParser parser = new PDFStreamParser(page);
            parser.parse();
            List<Object> tokens = parser.getTokens();

            boolean inBT = false;
            float curY = 0, curX = 0, lineY = 0;
            int blockNum = 0;
            List<Object> operands = new java.util.ArrayList<>();

            for (Object t : tokens) {
                if (!(t instanceof Operator)) {
                    operands.add(t);
                    continue;
                }
                Operator op = (Operator) t;
                String name = op.getName();

                if ("BT".equals(name)) {
                    inBT = true; blockNum++; curY = 0; curX = 0; lineY = 0;
                    System.out.println("--- BT #" + blockNum + " ---");
                } else if ("ET".equals(name)) {
                    inBT = false;
                    System.out.println("--- ET ---");
                } else if (inBT) {
                    if ("Tm".equals(name) && operands.size() >= 6) {
                        curX = toF(operands.get(operands.size() - 2));
                        curY = toF(operands.get(operands.size() - 1));
                        lineY = curY;
                        System.out.println("  Tm X=" + curX + " Y=" + curY);
                    } else if ("Td".equals(name) && operands.size() >= 2) {
                        float tx = toF(operands.get(operands.size() - 2));
                        float ty = toF(operands.get(operands.size() - 1));
                        curX += tx; lineY += ty; curY = lineY;
                        System.out.println("  Td tx=" + tx + " ty=" + ty + " → X=" + curX + " Y=" + curY);
                    } else if ("Tf".equals(name) && operands.size() >= 2) {
                        System.out.println("  Tf font=" + operands.get(operands.size()-2) + " size=" + operands.get(operands.size()-1));
                    } else if ("Tj".equals(name) && operands.size() >= 1) {
                        Object o = operands.get(operands.size()-1);
                        String txt = (o instanceof COSString) ? ((COSString)o).getString() : "?";
                        System.out.println("  Tj Y=" + curY + " X=" + curX + " \"" + shorten(txt) + "\"");
                    } else if ("TJ".equals(name) && operands.size() >= 1) {
                        Object o = operands.get(operands.size()-1);
                        String txt = extractTJ(o);
                        System.out.println("  TJ Y=" + curY + " X=" + curX + " \"" + shorten(txt) + "\"");
                    }
                } else {
                    // Non-text operators
                    if ("re".equals(name) && operands.size() >= 4) {
                        System.out.println("PATH re x=" + toF(operands.get(operands.size()-4))
                            + " y=" + toF(operands.get(operands.size()-3))
                            + " w=" + toF(operands.get(operands.size()-2))
                            + " h=" + toF(operands.get(operands.size()-1)));
                    } else if ("f".equals(name) || "f*".equals(name) || "F".equals(name)) {
                        System.out.println("FILL " + name);
                    } else if ("rg".equals(name) && operands.size() >= 3) {
                        System.out.println("COLOR rg " + toF(operands.get(operands.size()-3))
                            + " " + toF(operands.get(operands.size()-2))
                            + " " + toF(operands.get(operands.size()-1)));
                    } else if ("cm".equals(name) && operands.size() >= 6) {
                        System.out.println("CM a=" + toF(operands.get(0))
                            + " d=" + toF(operands.get(3))
                            + " tx=" + toF(operands.get(4))
                            + " ty=" + toF(operands.get(5)));
                    }
                }
                operands.clear();
            }
        }
    }

    static float toF(Object o) { return (o instanceof COSNumber) ? ((COSNumber)o).floatValue() : 0f; }
    static String shorten(String s) { return s.length() > 60 ? s.substring(0, 60) + "..." : s; }
    static String extractTJ(Object o) {
        if (!(o instanceof COSArray)) return "?";
        COSArray arr = (COSArray) o;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            COSBase item = arr.get(i);
            if (item instanceof COSString) sb.append(((COSString)item).getString());
        }
        return sb.toString();
    }
}
