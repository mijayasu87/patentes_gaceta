package ec.gob.senadi.pdftool.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Elemento de un lote de patentes para generación por lote.
 * Contiene los datos mínimos para mostrar en la UI y para
 * cargar la información completa al momento de generar.
 */
public class BatchPatentItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String applicationNumber;
    private String tipoPatente;
    private String titulo;
    private int formId;
    private String formType;
    private boolean previouslyPublished;
    private String fechaSolicitud;

    public BatchPatentItem() {}

    public BatchPatentItem(PatentData data) {
        this.applicationNumber = data.getApplicationNumber();
        this.tipoPatente = data.getTipoPatente();
        this.titulo = data.getTitulo();
        this.formId = data.getFormId();
        this.formType = "Patentform";
        this.fechaSolicitud = data.getFechaSolicitud();
    }

    // ── Getters / Setters ──

    public String getApplicationNumber() { return applicationNumber; }
    public void setApplicationNumber(String v) { this.applicationNumber = v; }

    public String getTipoPatente() { return tipoPatente; }
    public void setTipoPatente(String v) { this.tipoPatente = v; }

    public String getTitulo() { return titulo; }
    public void setTitulo(String v) { this.titulo = v; }

    public int getFormId() { return formId; }
    public void setFormId(int v) { this.formId = v; }

    public String getFormType() { return formType; }
    public void setFormType(String v) { this.formType = v; }

    public boolean isPreviouslyPublished() { return previouslyPublished; }
    public void setPreviouslyPublished(boolean v) { this.previouslyPublished = v; }

    public String getFechaSolicitud() { return fechaSolicitud; }
    public void setFechaSolicitud(String v) { this.fechaSolicitud = v; }

    /** Título truncado a 60 caracteres para la UI. */
    public String getTituloCorto() {
        if (titulo == null) return "";
        return titulo.length() > 60 ? titulo.substring(0, 57) + "..." : titulo;
    }

    /** Detecta si este item es un Diseño Industrial basándose en el tipo de patente. */
    public boolean isDisenoIndustrial() {
        return tipoPatente != null && tipoPatente.toLowerCase().contains("diseño");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BatchPatentItem)) return false;
        BatchPatentItem that = (BatchPatentItem) o;
        if (applicationNumber != null && that.applicationNumber != null) {
            return applicationNumber.trim().equalsIgnoreCase(that.applicationNumber.trim());
        }
        if (formId > 0 && that.formId > 0) {
            return formId == that.formId;
        }
        return Objects.equals(titulo, that.titulo)
                && Objects.equals(tipoPatente, that.tipoPatente);
    }

    @Override
    public int hashCode() {
        if (applicationNumber != null && !applicationNumber.trim().isEmpty()) {
            return applicationNumber.trim().toUpperCase().hashCode();
        }
        if (formId > 0) {
            return Integer.hashCode(formId);
        }
        return Objects.hash(titulo, tipoPatente);
    }
}
