package ec.gob.senadi.pdftool.pdf;

/**
 * Unidad visual de figura para Diseños Industriales: bytes de la imagen
 * (PNG) y dimensiones físicas explícitas en puntos PDF.
 *
 * Desacopla el tamaño en píxeles de la imagen (que puede variar según el
 * DPI usado al renderizar) del tamaño físico que ocupa el dibujo original.
 * Sin esta separación, una página fuente de gran formato (ej. 875×1237 mm)
 * obligaba a renderizar a un DPI alto que producía PNGs de varios MB.
 */
public class FigureUnit {
    public byte[] imageBytes;
    public float widthPt;
    public float heightPt;

    public FigureUnit() { }

    public FigureUnit(byte[] imageBytes, float widthPt, float heightPt) {
        this.imageBytes = imageBytes;
        this.widthPt = widthPt;
        this.heightPt = heightPt;
    }
}
