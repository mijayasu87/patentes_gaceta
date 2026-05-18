package ec.gob.senadi.pdftool.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class IOUtils {
    private IOUtils() {}

    /** Copia un InputStream a un File (útil para uploads). */
    public static void copyStream(InputStream in, File dst) throws IOException {
        try (InputStream bin = new BufferedInputStream(in);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = bin.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
    }
}
