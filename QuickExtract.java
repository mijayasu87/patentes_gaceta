import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.File;
public class QuickExtract {
    public static void main(String[] args) throws Exception {
        PDDocument doc = PDDocument.load(new File(args[0]));
        PDFTextStripper s = new PDFTextStripper();
        s.setSortByPosition(true);
        String text = s.getText(doc);
        System.out.println(text.substring(0, Math.min(text.length(), 2000)));
        doc.close();
    }
}
