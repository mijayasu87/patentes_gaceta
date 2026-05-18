package ec.gob.senadi.pdftool.service;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import ec.gob.senadi.pdftool.model.GazettePublicationInfo;
import ec.gob.senadi.pdftool.model.PatentData;

/**
 * Servicio que consulta directamente la base de datos MySQL (iepi_formularios)
 * para localizar trámites por código de aplicación (ej. SENADI-2025-34937 o
 * IEPI-2017-12345).
 */
public class DatabaseSearchService {

    private static final Logger LOG = Logger.getLogger(DatabaseSearchService.class.getName());

    // ── Configuración de conexión ──────────────────────────────────
    // --- Conexión empresa (producción) ---
    // private static final String DB_URL =
    //         "jdbc:mysql://10.0.26.130:3306/iepi_formularios"
    //         + "?useSSL=false&useUnicode=true&characterEncoding=UTF-8";
    // private static final String DB_USER = "iepi-solicitudes";
    // private static final String DB_PASS = "5ad0d5c3fced39d5048f";
    // --- Conexión local (desarrollo) ---
    private static final String DB_URL
            = "jdbc:mysql://10.0.20.130:3306/iepi_formularios"
            + "?useSSL=false&useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "iepi-solicitudes";
    private static final String DB_PASS = "5ad0d5c3fced39d5048f";

    // --- Conexión a BD de gacetas (iepi_sipigaceta en localhost) ---
    private static final String GACETA_DB_URL
            = "jdbc:mysql://10.0.20.130:3306/iepi_sipigaceta"
            + "?useSSL=false&useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&allowPublicKeyRetrieval=true";
    private static final String GACETA_DB_USER = "iepi-solicitudes";
    private static final String GACETA_DB_PASS = "5ad0d5c3fced39d5048f";

    // ── Clase resultado ────────────────────────────────────────────
    public static class SearchResult implements Serializable {

        private static final long serialVersionUID = 1L;

        private String applicationNumber;
        private String formType;      // Patentform, Hallmarkform, etc.
        private int formId;
        private String status;        // ACTIVE, DELIVERED
        private int year;
        private int number;

        // Datos del formulario (patent_forms / hallmark_forms)
        private String formFileName;
        private String expedientDate;
        private String tabloidNumber;

        // Archivos de anexos (patent_annexes_data)
        private String memoriaTecnicaFile;    // annex_id=8
        private String reivindicacionesFile;  // annex_id=9
        private String dibujosFile;           // annex_id=10

        // ─ Getters y Setters ─
        public String getApplicationNumber() {
            return applicationNumber;
        }

        public void setApplicationNumber(String v) {
            this.applicationNumber = v;
        }

        public String getFormType() {
            return formType;
        }

        public void setFormType(String v) {
            this.formType = v;
        }

        public int getFormId() {
            return formId;
        }

        public void setFormId(int v) {
            this.formId = v;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String v) {
            this.status = v;
        }

        public int getYear() {
            return year;
        }

        public void setYear(int v) {
            this.year = v;
        }

        public int getNumber() {
            return number;
        }

        public void setNumber(int v) {
            this.number = v;
        }

        public String getFormFileName() {
            return formFileName;
        }

        public void setFormFileName(String v) {
            this.formFileName = v;
        }

        public String getExpedientDate() {
            return expedientDate;
        }

        public void setExpedientDate(String v) {
            this.expedientDate = v;
        }

        public String getTabloidNumber() {
            return tabloidNumber;
        }

        public void setTabloidNumber(String v) {
            this.tabloidNumber = v;
        }

        public String getMemoriaTecnicaFile() {
            return memoriaTecnicaFile;
        }

        public void setMemoriaTecnicaFile(String v) {
            this.memoriaTecnicaFile = v;
        }

        public String getReivindicacionesFile() {
            return reivindicacionesFile;
        }

        public void setReivindicacionesFile(String v) {
            this.reivindicacionesFile = v;
        }

        public String getDibujosFile() {
            return dibujosFile;
        }

        public void setDibujosFile(String v) {
            this.dibujosFile = v;
        }

        /**
         * Nombre legible del tipo de formulario
         */
        public String getFormTypeLabel() {
            if (formType == null) {
                return "Desconocido";
            }
            switch (formType) {
                case "Patentform":
                    return "Patente / Modelo de Utilidad";
                case "Hallmarkform":
                    return "Signo Distintivo";
                case "Renewalform":
                    return "Renovación";
                case "Scopeform":
                    return "Alcance";
                case "Playform":
                    return "Obra / Derechos de Autor";
                case "Oppositionform":
                    return "Oposición";
                case "Tutelageform":
                    return "Tutela";
                case "Denominationform":
                    return "Denominación de Origen";
                case "Voucher":
                    return "Comprobante";
                default:
                    return formType;
            }
        }

        /**
         * Indica si es una patente (tiene anexos de reivindicaciones)
         */
        public boolean isPatent() {
            return "Patentform".equals(formType);
        }
    }

    // ── Conexión ───────────────────────────────────────────────────
    private static volatile boolean driverRegistered = false;

    private Connection getConnection() throws SQLException {
        ensureDriver();
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    /**
     * Obtiene conexión a la BD de gacetas (iepi_sipigaceta en producción).
     */
    private Connection getGacetaConnection() throws SQLException {
        ensureDriver();
        return DriverManager.getConnection(GACETA_DB_URL, GACETA_DB_USER, GACETA_DB_PASS);
    }

    private void ensureDriver() throws SQLException {
        if (!driverRegistered) {
            synchronized (DatabaseSearchService.class) {
                if (!driverRegistered) {
                    try {
                        Class.forName("com.mysql.jdbc.Driver");
                        driverRegistered = true;
                    } catch (ClassNotFoundException e) {
                        throw new SQLException("No se pudo registrar el driver MySQL: "
                                + e.getMessage(), e);
                    }
                }
            }
        }
    }

    // ── Búsqueda principal ─────────────────────────────────────────
    /**
     * Busca un código de aplicación en la base de datos y extrae toda la
     * información asociada (formulario, reivindicaciones, etc.). Solo devuelve
     * trámites con estado DELIVERED.
     *
     * @return lista de resultados (puede tener varios), vacía si no se encontró
     * nada
     */
    public List<SearchResult> search(String code) throws SQLException {
        if (code == null || code.trim().isEmpty()) {
            return new ArrayList<>();
        }
        code = code.trim().toUpperCase();

        // Detectar si es un ID numérico (ej. 1072)
        boolean isNumericId = code.matches("\\d+");

        List<SearchResult> results = new ArrayList<>();

        try (Connection conn = getConnection()) {

            if (isNumericId) {
                // ── Búsqueda por ID de formulario ──
                int formId = Integer.parseInt(code);
                results = findByFormId(conn, formId);
            } else {
                // ── Búsqueda por código de aplicación ──
                SearchResult single = findApplication(conn, code);
                if (single != null) {
                    results.add(single);
                }
            }

            // Cargar datos completos para cada resultado
            for (SearchResult result : results) {
                String formTable = getFormTable(result.getFormType());
                if (formTable != null) {
                    loadFormData(conn, formTable, result.getFormId(), result);
                }
                String annexDataTable = getAnnexDataTable(result.getFormType());
                if (annexDataTable != null) {
                    loadAnnexData(conn, annexDataTable, result.getFormId(), result);
                }
            }
        }

        return results;
    }

    /**
     * Prueba la conexión a la base de datos.
     *
     * @return mensaje de éxito o error
     */
    public String testConnection() {
        try (Connection conn = getConnection()) {
            return "Conexión exitosa a " + conn.getMetaData().getURL();
        } catch (SQLException e) {
            return "Error de conexión: " + e.getMessage();
        }
    }

    // ── Descarga de archivos desde el servidor ─────────────────────
    /**
     * Rutas candidatas en el filesystem del servidor donde SIPIGA guarda los
     * PDFs subidos. Se prueban en orden con LOAD_FILE().
     */
    private static final String[] CANDIDATE_PATHS = {
        "/opt/sipiga/uploads/",
        "/opt/sipiga/web-app/uploads/",
        "/var/www/html/sipiga/uploads/",
        "/var/www/html/uploads/",
        "/var/sipiga/uploads/",
        "/srv/sipiga/uploads/",
        "/tmp/sipiga/",
        "/tmp/uploads/",
        "/home/sipiga/uploads/",
        "/opt/sipiga/files/",
        "/var/lib/sipiga/uploads/",
        "/opt/sipiga/",
        "/var/www/sipiga/uploads/",
        "/opt/forms/",
        "/var/forms/",
        "/opt/iepi/uploads/",
        "/var/iepi/uploads/",
        "" // último intento: solo el nombre del archivo (por si LOAD_FILE soporta la ruta tal cual)
    };

    /**
     * Ruta base descubierta que funciona (se cachea una vez encontrada)
     */
    private static volatile String discoveredBasePath = null;

    /**
     * Descarga un archivo PDF desde el filesystem del servidor MySQL usando la
     * función LOAD_FILE() de MySQL.
     *
     * @param fileName nombre del archivo (ej. "temp_1433176816.pdf")
     * @return contenido binario del PDF, o null si no se encontró / no se pudo
     * leer
     */
    public byte[] downloadFileFromServer(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }

        try (Connection conn = getConnection()) {

            // Si ya descubrimos la ruta base, intentar primero con esa
            if (discoveredBasePath != null) {
                byte[] data = tryLoadFile(conn, discoveredBasePath + fileName);
                if (data != null) {
                    return data;
                }
                // Si falló, resetear y re-descubrir
                discoveredBasePath = null;
            }

            // Probar cada ruta candidata
            for (String basePath : CANDIDATE_PATHS) {
                String fullPath = basePath + fileName;
                byte[] data = tryLoadFile(conn, fullPath);
                if (data != null) {
                    discoveredBasePath = basePath;
                    LOG.info("Ruta de archivos descubierta en servidor: " + basePath);
                    return data;
                }
            }

            LOG.warning("No se pudo localizar el archivo en el servidor: " + fileName
                    + ". LOAD_FILE puede requerir privilegio FILE o el archivo no está en secure_file_priv.");

        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error al intentar descargar archivo del servidor: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Intenta leer un archivo del servidor MySQL usando LOAD_FILE().
     */
    private byte[] tryLoadFile(Connection conn, String fullPath) {
        String sql = "SELECT LOAD_FILE(?) AS content";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] data = rs.getBytes("content");
                    if (data != null && data.length > 0) {
                        LOG.info("Archivo encontrado en servidor: " + fullPath
                                + " (" + data.length + " bytes)");
                        return data;
                    }
                }
            }
        } catch (SQLException e) {
            LOG.fine("LOAD_FILE falló para " + fullPath + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Obtiene información de diagnóstico sobre la configuración de LOAD_FILE en
     * el servidor MySQL (secure_file_priv, privilegios, etc.).
     */
    public String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {

            // Verificar secure_file_priv
            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'secure_file_priv'")) {
                if (rs.next()) {
                    sb.append("secure_file_priv = ").append(rs.getString(2)).append("\n");
                }
            }

            // Verificar privilegios FILE
            try (ResultSet rs = st.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                while (rs.next()) {
                    String grant = rs.getString(1);
                    sb.append("Grant: ").append(grant).append("\n");
                }
            }

            // Verificar datadir
            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'datadir'")) {
                if (rs.next()) {
                    sb.append("datadir = ").append(rs.getString(2)).append("\n");
                }
            }

        } catch (SQLException e) {
            sb.append("Error obteniendo diagnóstico: ").append(e.getMessage());
        }
        return sb.toString();
    }

    // ── Consultas individuales ─────────────────────────────────────
    /**
     * Busca por código de aplicación exacto. Solo devuelve si es DELIVERED. Si
     * no lo encuentra en applications, busca directamente en las tablas de
     * formularios (patent_forms, hallmark_forms, etc.).
     */
    private SearchResult findApplication(Connection conn, String code) throws SQLException {
        // 1. Buscar en applications (tabla principal)
        String sql = "SELECT application_number, table_name, owner_id, status, year, number "
                + "FROM applications WHERE application_number = ? AND status = 'DELIVERED'";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    SearchResult r = new SearchResult();
                    r.setApplicationNumber(rs.getString("application_number"));
                    r.setFormType(rs.getString("table_name"));
                    r.setFormId(rs.getInt("owner_id"));
                    r.setStatus(rs.getString("status"));
                    r.setYear(rs.getInt("year"));
                    r.setNumber(rs.getInt("number"));
                    return r;
                }
            }
        }

        // 2. Fallback: buscar directamente en tablas de formularios por application_number
        LOG.info("findApplication: No encontrado en applications para " + code
                + ", buscando en tablas de formularios...");

        String[][] formTables = {
            {"Patentform", "patent_forms"},
            {"Hallmarkform", "hallmark_forms"},
            {"Playform", "play_forms"},
            {"Oppositionform", "opposition_forms"},
            {"Scopeform", "scope_forms"},
            {"Renewalform", "renewal_forms"},
            {"Tutelageform", "tutelage_forms"},
            {"Denominationform", "denomination_forms"}
        };

        for (String[] pair : formTables) {
            String formType = pair[0];
            String tableName = pair[1];
            String sqlFb = "SELECT id, application_number, status FROM " + tableName
                    + " WHERE application_number = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlFb)) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        SearchResult r = new SearchResult();
                        r.setApplicationNumber(rs.getString("application_number"));
                        r.setFormType(formType);
                        r.setFormId(rs.getInt("id"));
                        r.setStatus(rs.getString("status"));
                        LOG.info("findApplication: Encontrado en " + tableName + " id="
                                + r.getFormId() + " status=" + r.getStatus());
                        return r;
                    }
                }
            } catch (SQLException e) {
                LOG.fine("findApplication: tabla " + tableName + " no consultable: " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * Busca un formulario directamente por su ID numérico. Recorre las tablas
     * de formularios (patent_forms, hallmark_forms, etc.) y al encontrar el
     * registro busca las aplicaciones asociadas DELIVERED en la tabla
     * applications. Puede devolver múltiples resultados si hay varias
     * aplicaciones para el mismo form ID.
     */
    private List<SearchResult> findByFormId(Connection conn, int formId) throws SQLException {
        List<SearchResult> results = new ArrayList<>();

        // Pares: formType → formTable
        String[][] formTables = {
            {"Patentform", "patent_forms"},
            {"Hallmarkform", "hallmark_forms"},
            {"Renewalform", "renewal_forms"},
            {"Scopeform", "scope_forms"},
            {"Playform", "play_forms"},
            {"Oppositionform", "opposition_forms"},
            {"Tutelageform", "tutelage_forms"},
            {"Denominationform", "denomination_forms"}
        };

        for (String[] pair : formTables) {
            String formType = pair[0];
            String tableName = pair[1];

            String checkSql = "SELECT id FROM " + tableName + " WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setInt(1, formId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        // Formulario existe — buscar aplicaciones DELIVERED asociadas
                        String appSql = "SELECT application_number, status, year, number "
                                + "FROM applications WHERE owner_id = ? AND table_name = ? "
                                + "AND status = 'DELIVERED'";
                        try (PreparedStatement ps2 = conn.prepareStatement(appSql)) {
                            ps2.setInt(1, formId);
                            ps2.setString(2, formType);
                            try (ResultSet rs2 = ps2.executeQuery()) {
                                while (rs2.next()) {
                                    SearchResult r = new SearchResult();
                                    r.setFormType(formType);
                                    r.setFormId(formId);
                                    r.setApplicationNumber(rs2.getString("application_number"));
                                    r.setStatus(rs2.getString("status"));
                                    r.setYear(rs2.getInt("year"));
                                    r.setNumber(rs2.getInt("number"));
                                    results.add(r);
                                }
                            }
                        }
                        // Si encontramos el formulario en esta tabla, no buscar en las demás
                        break;
                    }
                }
            } catch (SQLException e) {
                LOG.fine("Tabla " + tableName + " no consultable para ID " + formId + ": " + e.getMessage());
            }
        }
        return results;
    }

    private void loadFormData(Connection conn, String formTable,
            int formId, SearchResult result) throws SQLException {
        // Consultar solo la columna expedient_date que existe en todas las tablas de formularios
        String sql = "SELECT expedient_date FROM " + formTable + " WHERE id = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, formId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result.setExpedientDate(rs.getString("expedient_date"));
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING,
                    "No se pudo cargar datos del formulario desde {0}: {1}",
                    new Object[]{formTable, e.getMessage()});
        }
    }

    private void loadAnnexData(Connection conn, String annexDataTable,
            int formId, SearchResult result) throws SQLException {
        // Buscar los anexos relevantes: 8=Memoria Técnica, 9=Reivindicaciones, 10=Dibujos
        String formIdColumn = getFormIdColumn(annexDataTable);
        String annexIdColumn = getAnnexIdColumn(annexDataTable);

        String sql = "SELECT " + annexIdColumn + ", file "
                + "FROM " + annexDataTable
                + " WHERE " + formIdColumn + " = ? "
                + "AND " + annexIdColumn + " IN (8, 9, 10)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, formId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int annexId = rs.getInt(annexIdColumn);
                    String fileName = rs.getString("file");

                    switch (annexId) {
                        case 8:
                            result.setMemoriaTecnicaFile(fileName);
                            break;
                        case 9:
                            result.setReivindicacionesFile(fileName);
                            break;
                        case 10:
                            result.setDibujosFile(fileName);
                            break;
                    }
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING,
                    "No se pudo cargar anexos desde {0}: {1}",
                    new Object[]{annexDataTable, e.getMessage()});
        }
    }

    // ── Mapeo de nombres ───────────────────────────────────────────
    private String getFormTable(String formType) {
        if (formType == null) {
            return null;
        }
        switch (formType) {
            case "Patentform":
                return "patent_forms";
            case "Hallmarkform":
                return "hallmark_forms";
            case "Renewalform":
                return "renewal_forms";
            case "Scopeform":
                return "scope_forms";
            case "Playform":
                return "play_forms";
            case "Oppositionform":
                return "opposition_forms";
            case "Tutelageform":
                return "tutelage_forms";
            case "Denominationform":
                return "denomination_forms";
            default:
                return null;
        }
    }

    private String getAnnexDataTable(String formType) {
        if (formType == null) {
            return null;
        }
        switch (formType) {
            case "Patentform":
                return "patent_annexes_data";
            case "Hallmarkform":
                return "hallmark_annexes_data";
            case "Renewalform":
                return "renewal_annexes_data";
            case "Scopeform":
                return "scope_annexes_data";
            case "Playform":
                return "play_annexes_data";
            case "Oppositionform":
                return "opposition_annexes_data";
            case "Tutelageform":
                return "tutelage_annexes_data";
            case "Denominationform":
                return "denomination_annexes_data";
            default:
                return null;
        }
    }

    /**
     * Obtiene el nombre de la columna FK del formulario en la tabla de anexos
     */
    private String getFormIdColumn(String annexDataTable) {
        switch (annexDataTable) {
            case "patent_annexes_data":
                return "patent_form_id";
            case "hallmark_annexes_data":
                return "hallmark_form_id";
            case "renewal_annexes_data":
                return "renewal_form_id";
            case "scope_annexes_data":
                return "scope_form_id";
            case "play_annexes_data":
                return "play_form_id";
            case "opposition_annexes_data":
                return "opposition_form_id";
            case "tutelage_annexes_data":
                return "tutelage_form_id";
            case "denomination_annexes_data":
                return "denomination_form_id";
            default:
                return "patent_form_id";
        }
    }

    /**
     * Obtiene el nombre de la columna FK del tipo de anexo
     */
    private String getAnnexIdColumn(String annexDataTable) {
        switch (annexDataTable) {
            case "patent_annexes_data":
                return "patent_annex_id";
            case "hallmark_annexes_data":
                return "hallmark_annex_id";
            case "renewal_annexes_data":
                return "renewal_annex_id";
            case "scope_annexes_data":
                return "scope_annex_id";
            case "play_annexes_data":
                return "play_annex_id";
            case "opposition_annexes_data":
                return "opposition_annex_id";
            case "tutelage_annexes_data":
                return "tutelage_annex_id";
            case "denomination_annexes_data":
                return "denomination_annex_id";
            default:
                return "patent_annex_id";
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Verificación de publicación previa en gacetas ────────────────
    // ══════════════════════════════════════════════════════════════════
    /**
     * Consulta la BD de gacetas (iepi_sipigaceta) para determinar si una
     * patente ya fue publicada anteriormente, y devuelve la información
     * completa de la publicación (número de gaceta, fecha, etc.).
     *
     * Consulta iepi_sipigaceta.patent_forms.file_name y compara contra
     * variantes normalizadas del applicationNumber (con/sin prefijo
     * IEPI/SENADI, con/sin .pdf). No modifica la base de datos.
     *
     * @param applicationNumber código de trámite (ej. SENADI-2013-12748)
     * @return GazettePublicationInfo con los datos, o null si no fue publicada
     */
    public GazettePublicationInfo findGazettePublication(String applicationNumber) {
        if (applicationNumber == null || applicationNumber.trim().isEmpty()) {
            return null;
        }

        // Construir variantes con y sin .pdf para buscar en file_name
        List<String> candidates = buildSearchCandidates(applicationNumber.trim());
        if (candidates.isEmpty()) {
            return null;
        }

        // Agregar versiones con .pdf de cada variante
        List<String> allVariants = new ArrayList<>();
        for (String c : candidates) {
            allVariants.add(c);                // ej: IEPI-2016-10828
            allVariants.add(c + ".pdf");       // ej: IEPI-2016-10828.pdf
            allVariants.add(c + ".PDF");       // ej: IEPI-2016-10828.PDF
        }

        // Construir SQL: obtener file_name, draft (nro gaceta), tabloid_number, tabloid_status, expedient_date
        StringBuilder sql = new StringBuilder(
                "SELECT file_name, draft, tabloid_number, tabloid_status, expedient_date "
                + "FROM patent_forms WHERE UPPER(TRIM(file_name)) IN (");
        for (int i = 0; i < allVariants.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(") AND (tabloid_status IS NULL OR tabloid_status <> '1') LIMIT 1");

        try (Connection conn = getGacetaConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < allVariants.size(); i++) {
                ps.setString(i + 1, allVariants.get(i).toUpperCase());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    GazettePublicationInfo info = new GazettePublicationInfo();
                    info.setMatchedFileName(rs.getString("file_name"));
                    info.setGazetteNumber(rs.getString("draft"));
                    info.setTabloidNumber(rs.getString("tabloid_number"));
                    info.setTabloidStatus(rs.getString("tabloid_status"));
                    info.setExpedientDate(rs.getString("expedient_date"));
                    return info;
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error consultando publicación previa para "
                    + applicationNumber + ": " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Atajo de compatibilidad: devuelve true si la patente ya fue publicada.
     */
    public boolean wasPreviouslyPublished(String applicationNumber) {
        return findGazettePublication(applicationNumber) != null;
    }

    /**
     * Guarda un registro de publicación en gaceta en
     * iepi_sipigaceta.patent_forms.
     *
     * @param applicationNumber código de trámite (se almacena en file_name)
     * @param fechaPatente fecha de solicitud de la patente (expedient_date)
     * @param gazetteNumber número de gaceta (tabloid_number y draft)
     */
    public void saveGazettePublication(String applicationNumber, String fechaPatente,
            String gazetteNumber) throws SQLException {
        String sql = "INSERT INTO patent_forms (expedient_date, file_name, tabloid_number, draft, tabloid_status) "
                + "VALUES (?, ?, ?, ?, '0')";
        try (Connection conn = getGacetaConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fechaPatente);
            ps.setString(2, applicationNumber);
            ps.setString(3, gazetteNumber);
            ps.setString(4, gazetteNumber);
            ps.executeUpdate();
            LOG.info("Registro de gaceta guardado: " + applicationNumber
                    + " → gaceta N.° " + gazetteNumber);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── LOTE PERSISTIDO EN BD (tabloid_status = 1) ────────────────────
    // ══════════════════════════════════════════════════════════════════
    /**
     * Guarda un trámite pendiente en el lote (tabloid_status = '1'). Si ya
     * existe con status 1, no lo duplica.
     */
    public void savePendingBatchItem(String applicationNumber, String fechaPatente) throws SQLException {
        String checkSql = "SELECT COUNT(*) FROM patent_forms WHERE UPPER(TRIM(file_name)) = ? AND tabloid_status = '1'";
        try (Connection conn = getGacetaConnection(); PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, applicationNumber.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    LOG.info("Lote: trámite ya pendiente en BD, omitiendo INSERT: " + applicationNumber);
                    return;
                }
            }
        }
        String insertSql = "INSERT INTO patent_forms (expedient_date, file_name, tabloid_status) VALUES (?, ?, '1')";
        try (Connection conn = getGacetaConnection(); PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, fechaPatente);
            ps.setString(2, applicationNumber);
            ps.executeUpdate();
            LOG.info("Lote: trámite guardado como pendiente (status=1): " + applicationNumber);
        }
    }

    /**
     * Elimina un trámite pendiente del lote (tabloid_status = '1').
     */
    public void deletePendingBatchItem(String applicationNumber) throws SQLException {
        String sql = "DELETE FROM patent_forms WHERE UPPER(TRIM(file_name)) = ? AND tabloid_status = '1'";
        try (Connection conn = getGacetaConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, applicationNumber.trim().toUpperCase());
            int rows = ps.executeUpdate();
            LOG.info("Lote: trámite eliminado de pendientes (" + rows + " filas): " + applicationNumber);
        }
    }

    /**
     * Elimina TODOS los trámites pendientes del lote (tabloid_status = '1').
     */
    public void deleteAllPendingBatchItems() throws SQLException {
        String sql = "DELETE FROM patent_forms WHERE tabloid_status = '1'";
        try (Connection conn = getGacetaConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int rows = ps.executeUpdate();
            LOG.info("Lote: vaciado completo de pendientes (" + rows + " filas)");
        }
    }

    /**
     * Carga los números de solicitud de los trámites pendientes (tabloid_status
     * = '1').
     */
    public List<String> loadPendingBatchApplicationNumbers() throws SQLException {
        List<String> items = new ArrayList<>();
        String sql = "SELECT file_name FROM patent_forms WHERE tabloid_status = '1' ORDER BY id ASC";
        try (Connection conn = getGacetaConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String fn = rs.getString("file_name");
                if (fn != null && !fn.trim().isEmpty()) {
                    items.add(fn.trim());
                }
            }
        }
        LOG.info("Lote: cargados " + items.size() + " trámites pendientes desde BD");
        return items;
    }

    /**
     * Confirma la publicación del lote: cambia tabloid_status de '1' a '0' y
     * asigna el número de gaceta. Retorna la cantidad de filas actualizadas.
     */
    public int confirmPendingBatch(String gazetteNumber) throws SQLException {
        String sql = "UPDATE patent_forms SET tabloid_status = '0', tabloid_number = ?, draft = ? "
                + "WHERE tabloid_status = '1'";
        try (Connection conn = getGacetaConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, gazetteNumber);
            ps.setString(2, gazetteNumber);
            int rows = ps.executeUpdate();
            LOG.info("Lote: confirmada publicación en gaceta N.° " + gazetteNumber
                    + " (" + rows + " registros actualizados)");
            return rows;
        }
    }

    /**
     * Normaliza un file_name de la BD: quita .pdf, convierte a mayúsculas,
     * trim. Solo para comparación en memoria — no modifica la BD.
     */
    private static String normalizeFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String s = fileName.trim().toUpperCase();
        if (s.endsWith(".PDF")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }

    /**
     * A partir de un código (del usuario o de la BD), genera todas las
     * variantes válidas para comparación: - la parte numérica pura (sin
     * prefijo) - con prefijo IEPI- - con prefijo SENADI- Evita duplicados como
     * SENADI-SENADI-xxxx.
     */
    private static List<String> buildSearchCandidates(String input) {
        List<String> result = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return result;
        }

        String upper = input.trim().toUpperCase();
        if (upper.endsWith(".PDF")) {
            upper = upper.substring(0, upper.length() - 4);
        }

        // Extraer la parte numérica (sin prefijo)
        String numericPart;
        if (upper.startsWith("SENADI-")) {
            numericPart = upper.substring(7);
        } else if (upper.startsWith("IEPI-")) {
            numericPart = upper.substring(5);
        } else {
            numericPart = upper;
        }

        result.add(numericPart);
        result.add("IEPI-" + numericPart);
        result.add("SENADI-" + numericPart);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════
    // ── Carga completa de datos de patente desde la BD ───────────────
    // ══════════════════════════════════════════════════════════════════
    /**
     * Carga PatentData para un resultado de búsqueda existente. NOTA:
     * applications.owner_id NO corresponde a patent_forms.id; por eso buscamos
     * usando el application_number.
     * @param result
     * @return 
     * @throws java.sql.SQLException
     */
    public PatentData loadPatentData(SearchResult result) throws SQLException {
        if (result == null || !result.isPatent()) {
            return null;
        }
        try (Connection conn = getConnection()) {
            return loadPatentDataInternal(conn, result.getApplicationNumber());
        }
    }

    private PatentData loadPatentDataInternal(Connection conn, String applicationNumber) throws SQLException {
        LOG.info(() -> "loadPatentDataInternal: applicationNumber=" + applicationNumber);
        PatentData data = new PatentData();

        // 1. Datos principales de patent_forms + tipo resuelto via form_types → types
        String sql = "SELECT pf.id, pf.owner_id, pf.application_number, pf.title, pf.summary, "
                + "pf.application_date, pf.expedient, pf.international_classification, "
                + "pf.pct_number, pf.claims, pf.status, pf.patent_type_id AS raw_patent_type_id, "
                + "t.id AS real_type_id, t.name AS type_name, t.alias AS type_alias "
                + "FROM patent_forms pf "
                + "LEFT JOIN form_types ft ON pf.patent_type_id = ft.id "
                + "LEFT JOIN types t ON ft.type_id = t.id "
                + "WHERE pf.application_number = ?";

        int realFormId;
        int ownerId = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, applicationNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    LOG.warning(() -> "loadPatentDataInternal: NO SE ENCONTRO patent_forms.application_number=" + applicationNumber);
                    return null;
                }
                realFormId = rs.getInt("id");
                ownerId = rs.getInt("owner_id");
                LOG.info("loadPatentDataInternal: ENCONTRADO patent_forms.id=" + realFormId + " owner_id=" + ownerId + " title=" + rs.getString("title"));
                data.setFormId(realFormId);
                data.setApplicationNumber(rs.getString("application_number"));
                data.setTitulo(rs.getString("title"));
                data.setResumen(rs.getString("summary"));
                data.setFechaSolicitud(rs.getString("application_date"));
                data.setExpediente(rs.getString("expedient"));
                data.setClasificacionInternacional(rs.getString("international_classification"));
                data.setNumeroPct(rs.getString("pct_number"));
                data.setNumeroClaims(rs.getInt("claims"));
                data.setStatus(rs.getString("status"));
                data.setPatentTypeId(rs.getInt("real_type_id"));

                // Tipo de patente: desde types JOIN, fallback a resolvePatentTypeName
                String typeName = rs.getString("type_name");
                String typeAlias = rs.getString("type_alias");
                if (typeName == null || typeName.isEmpty()) {
                    int rawTypeId = rs.getInt("raw_patent_type_id");
                    LOG.info("loadPatentDataInternal: type_name NULL, raw_patent_type_id=" + rawTypeId);
                    String[] resolved = resolvePatentTypeName(conn, rawTypeId);
                    if (resolved != null) {
                        typeName = resolved[0];
                        typeAlias = resolved[1];
                        // Fijar patentTypeId desde el enum para que isDisenoIndustrial etc. funcione
                        PatentData.PatentType pt = PatentData.PatentType.fromAlias(typeAlias);
                        if (pt != PatentData.PatentType.DESCONOCIDO) {
                            data.setPatentTypeId(pt.getId());
                        }
                    }
                }
                data.setTipoPatente(typeName);
                data.setTipoPatenteAlias(typeAlias);
            }
        }

        // 2. Personas vinculadas (solicitantes, inventores, representante)
        loadPersonas(conn, realFormId, ownerId, data);

        // 3. Prioridades
        loadPrioridades(conn, realFormId, data);

        // 4. Archivos de anexos (reivindicaciones y dibujos)
        loadAnexosPatente(conn, realFormId, data);

        return data;
    }

    /**
     * Resuelve nombre y alias del tipo de patente. Cadena:
     * patent_forms.patent_type_id → form_types.id → form_types.type_id →
     * PatentType enum (La tabla types en producción está vacía, por eso se usa
     * el enum.)
     *
     * @return String[]{name, alias} o null si no se resuelve
     */
    private String[] resolvePatentTypeName(Connection conn, int rawId) {
        if (rawId <= 0) {
            return null;
        }

        // form_types.id = rawId → obtener type_id
        String sqlFt = "SELECT type_id FROM form_types WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlFt)) {
            ps.setInt(1, rawId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int typeId = rs.getInt("type_id");
                    LOG.info("resolvePatentTypeName: form_types.id=" + rawId + " → type_id=" + typeId);
                    if (typeId > 0) {
                        // Primero intentar tabla types (por si en algún ambiente tiene datos)
                        String[] result = lookupTypesById(conn, typeId);
                        if (result != null) {
                            return result;
                        }
                        // Fallback: usar el enum PatentType con el type_id
                        PatentData.PatentType pt = PatentData.PatentType.fromId(typeId);
                        if (pt != PatentData.PatentType.DESCONOCIDO) {
                            LOG.info("resolvePatentTypeName: resuelto por enum type_id=" + typeId + " → " + pt.getNombre());
                            return new String[]{pt.getNombre(), pt.getAlias()};
                        }
                    }
                } else {
                    LOG.info("resolvePatentTypeName: form_types.id=" + rawId + " NO ENCONTRADO");
                }
            }
        } catch (Exception e) {
            LOG.warning("resolvePatentTypeName: error en form_types: " + e.getMessage());
        }
        return null;
    }

    /**
     * Busca name y alias en types por id
     */
    private String[] lookupTypesById(Connection conn, int typeId) {
        String sql = "SELECT name, alias FROM types WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, typeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("name");
                    String alias = rs.getString("alias");
                    LOG.info("lookupTypesById: types.id=" + typeId + " → name=" + name + ", alias=" + alias);
                    return new String[]{name, alias};
                }
            }
        } catch (Exception e) {
            LOG.warning("lookupTypesById: error: " + e.getMessage());
        }
        return null;
    }

    private void loadPersonas(Connection conn, int formId, int ownerId, PatentData data) throws SQLException {
        // nationality en person contiene el ID de countries como texto, hay que resolverlo
        String sql = "SELECT pp.person_id, pp.type, pp.title_name, "
                + "p.name, p.nationality, "
                + "c.name AS country_name "
                + "FROM person_patent pp "
                + "JOIN person p ON pp.person_id = p.id "
                + "LEFT JOIN countries c ON CAST(p.nationality AS UNSIGNED) = c.id "
                + "WHERE pp.patent_form_id = ? "
                + "ORDER BY pp.type, pp.person_id";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, formId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PatentData.PersonaPatente persona = new PatentData.PersonaPatente();
                    persona.setPersonId(rs.getInt("person_id"));
                    persona.setTipo(rs.getString("type"));

                    // Preferir title_name si tiene valor, sino usar name de la tabla person
                    String titleName = rs.getString("title_name");
                    String personName = rs.getString("name");
                    persona.setNombre(
                            (titleName != null && !titleName.trim().isEmpty())
                            ? titleName.trim() : personName
                    );
                    // Usar el nombre del país resuelto desde countries
                    String countryName = rs.getString("country_name");
                    persona.setNacionalidad(countryName != null ? countryName : rs.getString("nationality"));

                    String type = persona.getTipo();
                    if ("APPLICANT".equals(type) || "CURRENT_APPLICANT".equals(type)) {
                        data.getSolicitantes().add(persona);
                    } else if ("INVENTOR".equals(type)) {
                        data.getInventores().add(persona);
                    } else if ("LAWYER".equals(type) || "ATTORNEY".equals(type)
                            || "AGENT".equals(type)) {
                        // Solo el primer representante
                        if (data.getRepresentante() == null) {
                            data.setRepresentante(persona);
                        }
                    }
                }
            }
        }

        // Fallback encadenado cuando no hay APPLICANT en person_patent:
        // 1) owner_id → person, 2) inventores, 3) representante
        if (data.getSolicitantes().isEmpty()) {
            boolean ownerFound = false;
            if (ownerId > 0) {
                LOG.info("loadPersonas: sin APPLICANT para formId=" + formId + ", fallback a owner_id=" + ownerId);
                String fallbackSql = "SELECT p.id, p.name, p.nationality, c.name AS country_name "
                        + "FROM person p "
                        + "LEFT JOIN countries c ON CAST(p.nationality AS UNSIGNED) = c.id "
                        + "WHERE p.id = ?";
                try (PreparedStatement ps = conn.prepareStatement(fallbackSql)) {
                    ps.setInt(1, ownerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            ownerFound = true;
                            PatentData.PersonaPatente persona = new PatentData.PersonaPatente();
                            persona.setPersonId(rs.getInt("id"));
                            persona.setTipo("APPLICANT");
                            persona.setNombre(rs.getString("name"));
                            String countryName = rs.getString("country_name");
                            persona.setNacionalidad(countryName != null ? countryName : rs.getString("nationality"));
                            data.getSolicitantes().add(persona);
                        }
                    }
                }
            }

            // Si owner tampoco existe, usar inventores como solicitantes
            if (!ownerFound && !data.getInventores().isEmpty()) {
                LOG.info("loadPersonas: owner_id=" + ownerId + " no existe en person, usando inventores como solicitantes");
                for (PatentData.PersonaPatente inv : data.getInventores()) {
                    PatentData.PersonaPatente sol = new PatentData.PersonaPatente();
                    sol.setPersonId(inv.getPersonId());
                    sol.setTipo("APPLICANT");
                    sol.setNombre(inv.getNombre());
                    sol.setNacionalidad(inv.getNacionalidad());
                    data.getSolicitantes().add(sol);
                }
            }

            // Último recurso: usar representante como solicitante
            if (data.getSolicitantes().isEmpty() && data.getRepresentante() != null) {
                LOG.info("loadPersonas: sin inventores, usando representante como solicitante");
                PatentData.PersonaPatente rep = data.getRepresentante();
                PatentData.PersonaPatente sol = new PatentData.PersonaPatente();
                sol.setPersonId(rep.getPersonId());
                sol.setTipo("APPLICANT");
                sol.setNombre(rep.getNombre());
                sol.setNacionalidad(rep.getNacionalidad());
                data.getSolicitantes().add(sol);
            }
        }
    }

    private void loadPrioridades(Connection conn, int formId, PatentData data) throws SQLException {
        String sql = "SELECT pp.number, pp.date, c.name AS country_name "
                + "FROM patent_priorities pp "
                + "LEFT JOIN countries c ON pp.country_id = c.id "
                + "WHERE pp.patent_id = ? "
                + "ORDER BY pp.date";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, formId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PatentData.Prioridad prio = new PatentData.Prioridad();
                    prio.setNumero(rs.getString("number"));
                    prio.setFecha(rs.getString("date"));
                    prio.setPais(rs.getString("country_name"));
                    data.getPrioridades().add(prio);
                }
            }
        }
    }

    private void loadAnexosPatente(Connection conn, int formId, PatentData data) throws SQLException {
        LOG.info("loadAnexosPatente: formId=" + formId);
        String sql = "SELECT patent_annex_id, file FROM patent_annexes_data "
                + "WHERE patent_form_id = ? AND patent_annex_id IN (9, 10)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, formId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    int annexId = rs.getInt("patent_annex_id");
                    String fileName = rs.getString("file");
                    LOG.info("loadAnexosPatente: annexId=" + annexId + " file=" + fileName);
                    if (annexId == 9) {
                        data.setReivindicacionesFile(fileName);
                    } else if (annexId == 10) {
                        data.setDibujosFile(fileName);
                    }
                }
                if (!found) {
                    LOG.warning("loadAnexosPatente: No se encontraron anexos 9/10 para formId=" + formId);
                }
            }
        }
    }
}
