package ec.gob.senadi.pdftool.model;

import java.io.Serializable;

/**
 * DTO que encapsula la información de una publicación previa en gaceta,
 * obtenida de iepi_sipigaceta.patent_forms.
 */
public class GazettePublicationInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String matchedFileName;     // file_name encontrado en la tabla
    private String gazetteNumber;       // draft (número de gaceta)
    private String tabloidNumber;       // tabloid_number
    private String tabloidStatus;       // tabloid_status (ej: "0", "P")
    private String expedientDate;       // expedient_date

    public GazettePublicationInfo() {}

    public boolean isPreviouslyPublished() {
        return gazetteNumber != null && !gazetteNumber.trim().isEmpty();
    }

    // ── Getters / Setters ──

    public String getMatchedFileName() { return matchedFileName; }
    public void setMatchedFileName(String v) { this.matchedFileName = v; }

    public String getGazetteNumber() { return gazetteNumber; }
    public void setGazetteNumber(String v) { this.gazetteNumber = v; }

    public String getTabloidNumber() { return tabloidNumber; }
    public void setTabloidNumber(String v) { this.tabloidNumber = v; }

    public String getTabloidStatus() { return tabloidStatus; }
    public void setTabloidStatus(String v) { this.tabloidStatus = v; }

    public String getExpedientDate() { return expedientDate; }
    public void setExpedientDate(String v) { this.expedientDate = v; }
}
