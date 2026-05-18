package ec.gob.senadi.pdftool.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO que contiene todos los datos de una patente extraídos directamente
 * de la base de datos iepi_formularios. Estos datos se usan para generar
 * la primera página del PDF de publicación.
 */
public class PatentData implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Tipos de patente (IDs de la tabla types) ──────────────────
    public enum PatentType {
        PATENTE_INVENCION(19, "PI", "Patente de Invención"),
        PATENTE_PCT(20, "PC", "Patente de Invención PCT en fase nacional"),
        MODELO_UTILIDAD(21, "MU", "Modelo de utilidad"),
        DISENO_INDUSTRIAL(22, "DI", "Diseño Industrial"),
        DESCONOCIDO(0, "", "Desconocido");

        private final int id;
        private final String alias;
        private final String nombre;

        PatentType(int id, String alias, String nombre) {
            this.id = id;
            this.alias = alias;
            this.nombre = nombre;
        }

        public int getId() { return id; }
        public String getAlias() { return alias; }
        public String getNombre() { return nombre; }

        public static PatentType fromId(int id) {
            for (PatentType t : values()) {
                if (t.id == id) return t;
            }
            return DESCONOCIDO;
        }

        public static PatentType fromAlias(String alias) {
            if (alias == null) return DESCONOCIDO;
            String upper = alias.trim().toUpperCase();
            for (PatentType t : values()) {
                if (t.alias.equals(upper)) return t;
            }
            return DESCONOCIDO;
        }
    }

    // ── Identificación ──────────────────────────────────────────────
    private int formId;
    private String applicationNumber;   // SENADI-2025-48394
    private int patentTypeId;           // types.id resuelto via form_types (19=PI, 20=PC, 21=MU, 22=DI)
    private String tipoPatente;         // "Patente de Invención", "Modelo de utilidad", etc.
    private String tipoPatenteAlias;    // PI, MU, DI, PC
    private String titulo;              // Título de la invención
    private String resumen;             // Resumen / Abstract
    private String fechaSolicitud;      // application_date
    private String expediente;          // expedient
    private String clasificacionInternacional;  // international_classification
    private String numeroPct;           // pct_number
    private int numeroClaims;           // claims (cantidad)
    private String status;              // DELIVERED, etc.

    // ── Personas vinculadas ───────────────────────────────────────
    private List<PersonaPatente> solicitantes = new ArrayList<>();
    private List<PersonaPatente> inventores = new ArrayList<>();
    private PersonaPatente representante;  // LAWYER, ATTORNEY o AGENT

    // ── Prioridades ───────────────────────────────────────────────
    private List<Prioridad> prioridades = new ArrayList<>();

    // ── Archivo de reivindicaciones ───────────────────────────────
    private String reivindicacionesFile;  // nombre del archivo PDF de reivindicaciones
    private String dibujosFile;           // nombre del archivo PDF de dibujos

    // ── Getters y Setters ─────────────────────────────────────────

    public int getFormId() { return formId; }
    public void setFormId(int formId) { this.formId = formId; }

    public String getApplicationNumber() { return applicationNumber; }
    public void setApplicationNumber(String applicationNumber) { this.applicationNumber = applicationNumber; }

    public int getPatentTypeId() { return patentTypeId; }
    public void setPatentTypeId(int patentTypeId) { this.patentTypeId = patentTypeId; }

    /** Devuelve el enum PatentType basado en patentTypeId */
    public PatentType getPatentType() { return PatentType.fromId(patentTypeId); }

    public boolean isPatenteInvencion() { return patentTypeId == PatentType.PATENTE_INVENCION.getId(); }
    public boolean isPatentePCT() { return patentTypeId == PatentType.PATENTE_PCT.getId(); }
    public boolean isModeloUtilidad() { return patentTypeId == PatentType.MODELO_UTILIDAD.getId(); }

    /**
     * Detecta Diseño Industrial usando el tipo resuelto desde la BD
     * (patent_forms → form_types → types).
     */
    public boolean isDisenoIndustrial() {
        return patentTypeId == PatentType.DISENO_INDUSTRIAL.getId();
    }

    /** PI, PC o MU — tipos que llevan reivindicaciones (y NO son DI) */
    public boolean tieneReivindicaciones() {
        return !isDisenoIndustrial() && (isPatenteInvencion() || isPatentePCT() || isModeloUtilidad());
    }

    public String getTipoPatente() {
        if (tipoPatente != null && !tipoPatente.isEmpty()) return tipoPatente;
        PatentType pt = getPatentType();
        return pt != PatentType.DESCONOCIDO ? pt.getNombre() : tipoPatente;
    }
    public void setTipoPatente(String tipoPatente) { this.tipoPatente = tipoPatente; }

    public String getTipoPatenteAlias() {
        if (tipoPatenteAlias != null && !tipoPatenteAlias.isEmpty()) return tipoPatenteAlias;
        PatentType pt = getPatentType();
        return pt != PatentType.DESCONOCIDO ? pt.getAlias() : tipoPatenteAlias;
    }
    public void setTipoPatenteAlias(String tipoPatenteAlias) { this.tipoPatenteAlias = tipoPatenteAlias; }

    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }

    public String getResumen() { return resumen; }
    public void setResumen(String resumen) { this.resumen = resumen; }

    public String getFechaSolicitud() { return fechaSolicitud; }
    public void setFechaSolicitud(String fechaSolicitud) { this.fechaSolicitud = fechaSolicitud; }

    /** Devuelve la fecha de solicitud formateada DD/MM/YYYY */
    public String getFechaSolicitudTexto() {
        return formatDate(fechaSolicitud);
    }

    public String getExpediente() { return expediente; }
    public void setExpediente(String expediente) { this.expediente = expediente; }

    public String getClasificacionInternacional() { return clasificacionInternacional; }
    public void setClasificacionInternacional(String clasificacionInternacional) {
        this.clasificacionInternacional = clasificacionInternacional;
    }

    public String getNumeroPct() { return numeroPct; }
    public void setNumeroPct(String numeroPct) { this.numeroPct = numeroPct; }

    public int getNumeroClaims() { return numeroClaims; }
    public void setNumeroClaims(int numeroClaims) { this.numeroClaims = numeroClaims; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<PersonaPatente> getSolicitantes() { return solicitantes; }
    public void setSolicitantes(List<PersonaPatente> solicitantes) { this.solicitantes = solicitantes; }

    public List<PersonaPatente> getInventores() { return inventores; }
    public void setInventores(List<PersonaPatente> inventores) { this.inventores = inventores; }

    public PersonaPatente getRepresentante() { return representante; }
    public void setRepresentante(PersonaPatente representante) { this.representante = representante; }

    public List<Prioridad> getPrioridades() { return prioridades; }
    public void setPrioridades(List<Prioridad> prioridades) { this.prioridades = prioridades; }

    public String getReivindicacionesFile() { return reivindicacionesFile; }
    public void setReivindicacionesFile(String reivindicacionesFile) { this.reivindicacionesFile = reivindicacionesFile; }

    public String getDibujosFile() { return dibujosFile; }
    public void setDibujosFile(String dibujosFile) { this.dibujosFile = dibujosFile; }

    private static final String BASE_URL = "https://registro.propiedadintelectual.gob.ec/solicitudes/media/files/patent_forms/";

    /** Construye la URL pública de descarga de reivindicaciones */
    public String getReivindicacionesUrl() {
        if (reivindicacionesFile == null || reivindicacionesFile.isEmpty() || formId <= 0) return null;
        return BASE_URL + formId + "/" + reivindicacionesFile;
    }

    /** Construye la URL pública de descarga de dibujos */
    public String getDibujosUrl() {
        if (dibujosFile == null || dibujosFile.isEmpty() || formId <= 0) return null;
        return BASE_URL + formId + "/" + dibujosFile;
    }

    // ── Métodos de conveniencia ───────────────────────────────────

    /** Devuelve todos los nombres de solicitantes separados por "; " */
    public String getSolicitantesTexto() {
        if (solicitantes.isEmpty()) return "(sin solicitantes)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < solicitantes.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(solicitantes.get(i).getNombre());
        }
        return sb.toString();
    }

    /** Devuelve todos los países de los solicitantes, eliminando duplicados */
    public String getPaisesTexto() {
        if (solicitantes.isEmpty()) return "(sin país)";
        List<String> paises = new ArrayList<>();
        for (PersonaPatente sol : solicitantes) {
            String pais = sol.getNacionalidad();
            if (pais != null && !pais.isEmpty() && !paises.contains(pais)) {
                paises.add(pais);
            }
        }
        if (paises.isEmpty()) return "(sin país)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paises.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(paises.get(i));
        }
        return sb.toString();
    }

    /** Devuelve la primera fecha de prioridad formateada DD/MM/YYYY, o null */
    public String getFechaPrioridadTexto() {
        if (prioridades.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < prioridades.size(); i++) {
            if (i > 0) sb.append("; ");
            Prioridad p = prioridades.get(i);
            sb.append(formatDate(p.getFecha()));
        }
        return sb.toString();
    }

    /** Formatea fecha de "YYYY-MM-DD ..." a "DD/MM/YYYY" */
    private String formatDate(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // Tomar solo la parte de fecha (sin hora)
        String dateOnly = raw.contains(" ") ? raw.substring(0, raw.indexOf(' ')) : raw;
        String[] parts = dateOnly.split("-");
        if (parts.length >= 3) {
            return parts[2] + "/" + parts[1] + "/" + parts[0];
        }
        return raw;
    }

    /** Nombre del representante / apoderado. Si no hay representante explícito, usa el primer solicitante. */
    public String getRepresentanteTexto() {
        if (representante != null) return representante.getNombre();
        if (!solicitantes.isEmpty()) return solicitantes.get(0).getNombre();
        return "(sin representante)";
    }

    /** Nombres de inventores separados por "; " */
    public String getInventoresTexto() {
        if (inventores.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inventores.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(inventores.get(i).getNombre());
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Clases internas ──────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    public static class PersonaPatente implements Serializable {
        private static final long serialVersionUID = 1L;

        private int personId;
        private String nombre;        // person.name (o title_name si existe)
        private String nacionalidad;  // person.nationality
        private String tipo;          // APPLICANT, INVENTOR, LAWYER, ATTORNEY, AGENT

        public int getPersonId() { return personId; }
        public void setPersonId(int personId) { this.personId = personId; }

        public String getNombre() { return nombre; }
        public void setNombre(String nombre) { this.nombre = nombre; }

        public String getNacionalidad() { return nacionalidad; }
        public void setNacionalidad(String nacionalidad) { this.nacionalidad = nacionalidad; }

        public String getTipo() { return tipo; }
        public void setTipo(String tipo) { this.tipo = tipo; }
    }

    public static class Prioridad implements Serializable {
        private static final long serialVersionUID = 1L;

        private String pais;     // countries.name
        private String numero;   // patent_priorities.number
        private String fecha;    // patent_priorities.date

        public String getPais() { return pais; }
        public void setPais(String pais) { this.pais = pais; }

        public String getNumero() { return numero; }
        public void setNumero(String numero) { this.numero = numero; }

        public String getFecha() { return fecha; }
        public void setFecha(String fecha) { this.fecha = fecha; }
    }
}
