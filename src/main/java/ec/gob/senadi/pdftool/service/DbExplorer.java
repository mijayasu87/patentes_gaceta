package ec.gob.senadi.pdftool.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Clase temporal para explorar la estructura de la base de datos. Ejecutar
 * directamente con: mvn exec:java
 * -Dexec.mainClass="ec.gob.senadi.pdftool.service.DbExplorer"
 */
public class DbExplorer {

    // --- Conexión empresa (producción) ---
    private static final String DB_URL
            = "jdbc:mysql://10.0.20.130:3306/iepi_formularios"
            + "?useSSL=false&useUnicode=true&characterEncoding=UTF-8";
    private static final String DB_USER = "iepi-solicitudes";
    private static final String DB_PASS = "5ad0d5c3fced39d5048f";

    // --- Conexión local (desarrollo) ---
    // private static final String DB_URL =
    //         "jdbc:mysql://localhost:3306/iepi_formularios"
    //         + "?useSSL=false&useUnicode=true&characterEncoding=UTF-8";
    // private static final String DB_USER = "root";
    // private static final String DB_PASS = "123";
    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.jdbc.Driver");

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            if (args.length > 0) {
                // Ejecutar el SQL pasado como argumento
                String sql = args[0];
                System.out.println("SQL> " + sql + "\n");
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    // Header
                    StringBuilder header = new StringBuilder();
                    for (int i = 1; i <= cols; i++) {
                        if (i > 1) {
                            header.append(" | ");
                        }
                        header.append(md.getColumnLabel(i));
                    }
                    System.out.println(header);
                    System.out.println("-".repeat(header.length()));
                    // Rows
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= cols; i++) {
                            if (i > 1) {
                                row.append(" | ");
                            }
                            row.append(rs.getString(i));
                        }
                        System.out.println(row);
                    }
                }
                return;
            }
            System.out.println("Uso: DbExplorer \"SELECT ...\"");
        }
    }

    private static void showColumns(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("DESCRIBE " + table)) {
            while (rs.next()) {
                System.out.println("  " + rs.getString("Field") + " : " + rs.getString("Type")
                        + (rs.getString("Key").isEmpty() ? "" : " [" + rs.getString("Key") + "]"));
            }
        }
    }
}
