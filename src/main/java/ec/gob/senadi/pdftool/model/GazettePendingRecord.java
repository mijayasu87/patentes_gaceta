package ec.gob.senadi.pdftool.model;

import java.io.Serializable;

/**
 * Registro pendiente de publicación en gaceta.
 * Se almacena en memoria (application-scope) cuando un usuario genera un lote.
 */
public class GazettePendingRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private String applicationNumber;
    private String fechaPatente;      // expedient_date (fecha de solicitud)
    private String titulo;
    private String tipoPatente;
    private String registradoPor;     // usuario que generó el lote
    private String fechaRegistro;     // timestamp de cuándo se agregó
    private boolean savedToDb;        // ya guardado en BD de gaceta

    public GazettePendingRecord() {}

    // ── Getters / Setters ──

    public String getApplicationNumber()         { return applicationNumber; }
    public void setApplicationNumber(String v)    { this.applicationNumber = v; }

    public String getFechaPatente()              { return fechaPatente; }
    public void setFechaPatente(String v)         { this.fechaPatente = v; }

    public String getTitulo()                    { return titulo; }
    public void setTitulo(String v)               { this.titulo = v; }

    public String getTipoPatente()               { return tipoPatente; }
    public void setTipoPatente(String v)          { this.tipoPatente = v; }

    public String getRegistradoPor()             { return registradoPor; }
    public void setRegistradoPor(String v)        { this.registradoPor = v; }

    public String getFechaRegistro()             { return fechaRegistro; }
    public void setFechaRegistro(String v)        { this.fechaRegistro = v; }

    public boolean isSavedToDb()                 { return savedToDb; }
    public void setSavedToDb(boolean v)           { this.savedToDb = v; }

    /** Título truncado para la tabla de administración. */
    public String getTituloCorto() {
        if (titulo == null) return "";
        return titulo.length() > 55 ? titulo.substring(0, 52) + "..." : titulo;
    }
}
