package ec.gob.senadi.pdftool.service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio que busca dentro del dump SQL (base.sql) de la base de datos
 * iepi_sipigaceta para localizar trámites por código de aplicación
 * (ej. SENADI-2025-34937 o IEPI-2017-12345).
 */
public class SqlDumpSearchService {

    private final String baseSqlPath;

    public SqlDumpSearchService(String baseSqlPath) {
        this.baseSqlPath = baseSqlPath;
    }

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

        public String getApplicationNumber()        { return applicationNumber; }
        public void   setApplicationNumber(String v) { this.applicationNumber = v; }

        public String getFormType()        { return formType; }
        public void   setFormType(String v) { this.formType = v; }

        public int  getFormId()     { return formId; }
        public void setFormId(int v) { this.formId = v; }

        public String getStatus()        { return status; }
        public void   setStatus(String v) { this.status = v; }

        public int  getYear()      { return year; }
        public void setYear(int v)  { this.year = v; }

        public int  getNumber()     { return number; }
        public void setNumber(int v) { this.number = v; }

        public String getFormFileName()        { return formFileName; }
        public void   setFormFileName(String v) { this.formFileName = v; }

        public String getExpedientDate()        { return expedientDate; }
        public void   setExpedientDate(String v) { this.expedientDate = v; }

        public String getTabloidNumber()        { return tabloidNumber; }
        public void   setTabloidNumber(String v) { this.tabloidNumber = v; }

        public String getMemoriaTecnicaFile()        { return memoriaTecnicaFile; }
        public void   setMemoriaTecnicaFile(String v) { this.memoriaTecnicaFile = v; }

        public String getReivindicacionesFile()        { return reivindicacionesFile; }
        public void   setReivindicacionesFile(String v) { this.reivindicacionesFile = v; }

        public String getDibujosFile()        { return dibujosFile; }
        public void   setDibujosFile(String v) { this.dibujosFile = v; }

        /** Nombre legible del tipo de formulario */
        public String getFormTypeLabel() {
            if (formType == null) return "Desconocido";
            switch (formType) {
                case "Patentform":       return "Patente / Modelo de Utilidad";
                case "Hallmarkform":     return "Signo Distintivo";
                case "Renewalform":      return "Renovación";
                case "Scopeform":        return "Alcance";
                case "Playform":         return "Obra / Derechos de Autor";
                case "Oppositionform":   return "Oposición";
                case "Tutelageform":     return "Tutela";
                case "Denominationform": return "Denominación de Origen";
                case "Voucher":          return "Comprobante";
                default:                 return formType;
            }
        }

        /** Indica si es una patente (tiene anexos de reivindicaciones) */
        public boolean isPatent() {
            return "Patentform".equals(formType);
        }
    }

    // ── Búsqueda principal ─────────────────────────────────────────

    /**
     * Busca un código de aplicación en el dump SQL y extrae toda la
     * información asociada (formulario, reivindicaciones, etc.).
     */
    public SearchResult search(String code) throws IOException {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        code = code.trim().toUpperCase();

        SearchResult result = null;
        String codeLiteral = "'" + code + "'";

        String targetFormTable = null;
        String targetAnnexDataTable = null;
        int targetFormId = -1;

        boolean foundForm = false;
        boolean foundAnnex = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(baseSqlPath), "UTF-8"),
                1024 * 1024)) {  // 1 MB buffer

            String line;
            while ((line = reader.readLine()) != null) {

                // ── Paso 1: Buscar en tabla applications ──
                if (result == null
                        && line.contains(codeLiteral)
                        && line.contains("`applications`")) {

                    result = parseApplication(line, code);
                    if (result != null) {
                        targetFormTable = getFormTable(result.getFormType());
                        targetAnnexDataTable = getAnnexDataTable(result.getFormType());
                        targetFormId = result.getFormId();
                    }
                }

                // ── Paso 2: Buscar datos del formulario ──
                if (result != null && !foundForm
                        && targetFormTable != null
                        && line.contains("`" + targetFormTable + "`")
                        && line.contains("INSERT INTO")) {

                    foundForm = parseFormData(line, targetFormId, result);
                }

                // ── Paso 3: Buscar anexos (reivindicaciones, memoria, etc.) ──
                if (result != null && !foundAnnex
                        && targetAnnexDataTable != null
                        && line.contains("`" + targetAnnexDataTable + "`")
                        && line.contains("INSERT INTO")) {

                    foundAnnex = parseAnnexData(line, targetFormId, result);
                }

                // Si ya tenemos todo, salir temprano
                if (result != null && foundForm && foundAnnex) {
                    break;
                }
            }
        }

        return result;
    }

    // ── Parsers internos ───────────────────────────────────────────

    private SearchResult parseApplication(String line, String code) {
        // Formato: ('CODE','FormType',ID,'STATUS',YEAR,NUMBER,'SERVICEWINDOW')
        Pattern p = Pattern.compile(
                "\\('" + Pattern.quote(code) + "','(\\w+)',(\\d+),'(\\w+)',(\\d+),(\\d+),'(\\w+)'\\)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            SearchResult r = new SearchResult();
            r.setApplicationNumber(code);
            r.setFormType(m.group(1));
            r.setFormId(Integer.parseInt(m.group(2)));
            r.setStatus(m.group(3));
            r.setYear(Integer.parseInt(m.group(4)));
            r.setNumber(Integer.parseInt(m.group(5)));
            return r;
        }
        return null;
    }

    private boolean parseFormData(String line, int formId, SearchResult result) {
        // patent_forms: (id,'expedient_date','file_name','tabloid_number','tabloid_status','draft')
        // Buscamos: (FORM_ID,'DATE','FILENAME','TABLOID',...)
        Pattern p = Pattern.compile(
                "\\(" + formId + ",'([^']*?)','([^']*?)','([^']*?)','([^']*?)','([^']*?)'\\)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            result.setExpedientDate(m.group(1));
            result.setFormFileName(m.group(2));
            result.setTabloidNumber(m.group(3));
            return true;
        }
        return false;
    }

    private boolean parseAnnexData(String line, int formId, SearchResult result) {
        boolean found = false;

        // Memoria Técnica (annex_id = 8)
        Pattern p8 = Pattern.compile("\\(" + formId + ",8,'([^']+)'\\)");
        Matcher m8 = p8.matcher(line);
        if (m8.find()) {
            result.setMemoriaTecnicaFile(m8.group(1));
            found = true;
        }

        // Reivindicaciones (annex_id = 9)
        Pattern p9 = Pattern.compile("\\(" + formId + ",9,'([^']+)'\\)");
        Matcher m9 = p9.matcher(line);
        if (m9.find()) {
            result.setReivindicacionesFile(m9.group(1));
            found = true;
        }

        // Dibujos (annex_id = 10)
        Pattern p10 = Pattern.compile("\\(" + formId + ",10,'([^']+)'\\)");
        Matcher m10 = p10.matcher(line);
        if (m10.find()) {
            result.setDibujosFile(m10.group(1));
            found = true;
        }

        return found;
    }

    // ── Mapeo de nombres ───────────────────────────────────────────

    private String getFormTable(String formType) {
        if (formType == null) return null;
        switch (formType) {
            case "Patentform":       return "patent_forms";
            case "Hallmarkform":     return "hallmark_forms";
            case "Renewalform":      return "renewal_forms";
            case "Scopeform":        return "scope_forms";
            case "Playform":         return "play_forms";
            case "Oppositionform":   return "opposition_forms";
            case "Tutelageform":     return "tutelage_forms";
            case "Denominationform": return "denomination_forms";
            default:                 return null;
        }
    }

    private String getAnnexDataTable(String formType) {
        if (formType == null) return null;
        switch (formType) {
            case "Patentform":       return "patent_annexes_data";
            case "Hallmarkform":     return "hallmark_annexes_data";
            case "Renewalform":      return "renewal_annexes_data";
            case "Scopeform":        return "scope_annexes_data";
            case "Playform":         return "play_annexes_data";
            case "Oppositionform":   return "opposition_annexes_data";
            case "Tutelageform":     return "tutelage_annexes_data";
            case "Denominationform": return "denomination_annexes_data";
            default:                 return null;
        }
    }
}
