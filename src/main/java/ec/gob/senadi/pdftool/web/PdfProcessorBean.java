package ec.gob.senadi.pdftool.web;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.SessionScoped;
import javax.faces.application.FacesMessage;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;

import org.primefaces.PrimeFaces;
import org.primefaces.model.file.UploadedFile;

import ec.gob.senadi.pdftool.model.BatchPatentItem;
import ec.gob.senadi.pdftool.model.GazettePublicationInfo;
import ec.gob.senadi.pdftool.model.PatentData;
import ec.gob.senadi.pdftool.service.DatabaseSearchService;
import ec.gob.senadi.pdftool.service.DatabaseSearchService.SearchResult;
import ec.gob.senadi.pdftool.service.PdfProcessingService;
import ec.gob.senadi.pdftool.util.IOUtils;

@Named("pdfBean")
@SessionScoped
public class PdfProcessorBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(PdfProcessorBean.class.getName());

    @Inject
    private AuthBean authBean;

    private String modo = "patente";
    private boolean ocultarContacto = false;
    private transient UploadedFile archivoFormulario;
    private transient UploadedFile archivoSegundoPdf;

    // ── Campos de entrada manual (Tab 2) ─────────────────────────────
    private String manTipoPatente = "Patente de Invención";
    private String manNumeroSolicitud;
    private String manFechaSolicitud;
    private String manTitulo;
    private String manClasificacion;
    private String manSolicitante;
    private String manPais;
    private String manFechaPrioridad;
    private String manRepresentante;
    private String manResumen;
    private transient UploadedFile manSegundoPdf;

    private final PdfProcessingService service = new PdfProcessingService();

    // ── Directorios locales de cache ─────────────────────────────────
    private static final String BASE_DIR = "C:\\Users\\matsa\\Desktop\\PROYECTO SENADI";
    private static final String REIVINDICACIONES_DIR = BASE_DIR + "\\PDF\\REIVINDICACIONES";
    private static final String DIBUJOS_DIR = BASE_DIR + "\\PDF\\DIBUJOS";

    private final DatabaseSearchService dbService = new DatabaseSearchService();

    // ── Estado de búsqueda ───────────────────────────────────────────
    private String codigoBusqueda;
    private List<SearchResult> resultadosBusqueda = new ArrayList<>();
    private SearchResult resultadoSeleccionado;
    private String mensajeBusqueda;
    private boolean buscando = false;

    /**
     * Datos completos de la patente cargados desde la BD
     */
    private PatentData patentData;

    /**
     * Información de publicación previa en gaceta (null si no fue publicada)
     */
    private GazettePublicationInfo gazetteInfo;

    // ── Estado del lote ───────────────────────────────────────────────
    private List<BatchPatentItem> lotePat = new ArrayList<>();
    private String tipoLote = "UTILITY"; // "UTILITY" = Patentes inv/util, "DESIGN" = Diseños industriales

    /**
     * Item pendiente de confirmación por publicación previa
     */
    private BatchPatentItem pendienteConfirmacion;

    /**
     * Número de gaceta para guardar en BD (solo admin)
     */
    private String numeroGacetaGuardar;

    /**
     * Índice del item pendiente de eliminación (diálogo de confirmación)
     */
    private int indicePendienteEliminar = -1;

    // ── Estado del lote "Datos con hojas en blanco" (temporal, sin BD) ──
    private List<BatchPatentItem> loteBlank = new ArrayList<>();
    private String codigoBusquedaBlank;
    private List<SearchResult> resultadosBusquedaBlank = new ArrayList<>();
    private SearchResult resultadoSeleccionadoBlank;
    private String mensajeBusquedaBlank;
    private PatentData patentDataBlank;

    public PdfProcessorBean() {
        codigoBusqueda = "";
        patentData = null;
    }

    // ── Getters y Setters ───────────────────────────────────────────
    public String getModo() {
        return modo;
    }

    public void setModo(String modo) {
        this.modo = modo;
    }

    public boolean isOcultarContacto() {
        return ocultarContacto;
    }

    public void setOcultarContacto(boolean ocultarContacto) {
        this.ocultarContacto = ocultarContacto;
    }

    public UploadedFile getArchivoFormulario() {
        return archivoFormulario;
    }

    public void setArchivoFormulario(UploadedFile archivoFormulario) {
        this.archivoFormulario = archivoFormulario;
    }

    public UploadedFile getArchivoSegundoPdf() {
        return archivoSegundoPdf;
    }

    public void setArchivoSegundoPdf(UploadedFile archivoSegundoPdf) {
        this.archivoSegundoPdf = archivoSegundoPdf;
    }

    // ── Getters/Setters campos manuales ──────────────────────────────
    public String getManTipoPatente() {
        return manTipoPatente;
    }

    public void setManTipoPatente(String v) {
        this.manTipoPatente = v;
    }

    public String getManNumeroSolicitud() {
        return manNumeroSolicitud;
    }

    public void setManNumeroSolicitud(String v) {
        this.manNumeroSolicitud = v;
    }

    public String getManFechaSolicitud() {
        return manFechaSolicitud;
    }

    public void setManFechaSolicitud(String v) {
        this.manFechaSolicitud = v;
    }

    public String getManTitulo() {
        return manTitulo;
    }

    public void setManTitulo(String v) {
        this.manTitulo = v;
    }

    public String getManClasificacion() {
        return manClasificacion;
    }

    public void setManClasificacion(String v) {
        this.manClasificacion = v;
    }

    public String getManSolicitante() {
        return manSolicitante;
    }

    public void setManSolicitante(String v) {
        this.manSolicitante = v;
    }

    public String getManPais() {
        return manPais;
    }

    public void setManPais(String v) {
        this.manPais = v;
    }

    public String getManFechaPrioridad() {
        return manFechaPrioridad;
    }

    public void setManFechaPrioridad(String v) {
        this.manFechaPrioridad = v;
    }

    public String getManRepresentante() {
        return manRepresentante;
    }

    public void setManRepresentante(String v) {
        this.manRepresentante = v;
    }

    public String getManResumen() {
        return manResumen;
    }

    public void setManResumen(String v) {
        this.manResumen = v;
    }

    public UploadedFile getManSegundoPdf() {
        return manSegundoPdf;
    }

    public void setManSegundoPdf(UploadedFile v) {
        this.manSegundoPdf = v;
    }

    public String getCodigoBusqueda() {
        return codigoBusqueda;
    }

    public void setCodigoBusqueda(String codigoBusqueda) {
        this.codigoBusqueda = codigoBusqueda;
    }

    public List<SearchResult> getResultadosBusqueda() {
        return resultadosBusqueda;
    }

    public SearchResult getResultadoSeleccionado() {
        return resultadoSeleccionado;
    }

    public void setResultadoSeleccionado(SearchResult r) {
        this.resultadoSeleccionado = r;
    }

    public String getMensajeBusqueda() {
        return mensajeBusqueda;
    }

    public boolean isBuscando() {
        return buscando;
    }

    public PatentData getPatentData() {
        return patentData;
    }

    public void setPatentData(PatentData patentData) {
        this.patentData = patentData;
    }

    public boolean isPublicadaEnGaceta() {
        return gazetteInfo != null;
    }

    public GazettePublicationInfo getGazetteInfo() {
        return gazetteInfo;
    }

    public List<BatchPatentItem> getLotePat() {
        return lotePat;
    }

    public boolean isLoteVacio() {
        return lotePat.isEmpty();
    }

    public String getTipoLote() {
        return tipoLote;
    }

    public void setTipoLote(String tipoLote) {
        this.tipoLote = tipoLote;
    }

    /**
     * Binding booleano para el p:toggleSwitch: true = DESIGN, false = UTILITY
     */
    public boolean isTipoLoteDesign() {
        return "DESIGN".equals(tipoLote);
    }

    public void setTipoLoteDesign(boolean design) {
        this.tipoLote = design ? "DESIGN" : "UTILITY";
    }

    /**
     * Para compatibilidad con la UI (alias del seleccionado).
     */
    public SearchResult getResultadoBusqueda() {
        return resultadoSeleccionado;
    }

    // ── Getters/Setters: lote "Datos con hojas en blanco" ───────────
    public List<BatchPatentItem> getLoteBlank() {
        return loteBlank;
    }

    public boolean isLoteBlankVacio() {
        return loteBlank.isEmpty();
    }

    public String getCodigoBusquedaBlank() {
        return codigoBusquedaBlank;
    }

    public void setCodigoBusquedaBlank(String v) {
        this.codigoBusquedaBlank = v;
    }

    public List<SearchResult> getResultadosBusquedaBlank() {
        return resultadosBusquedaBlank;
    }

    public SearchResult getResultadoSeleccionadoBlank() {
        return resultadoSeleccionadoBlank;
    }

    public void setResultadoSeleccionadoBlank(SearchResult v) {
        this.resultadoSeleccionadoBlank = v;
    }

    public String getMensajeBusquedaBlank() {
        return mensajeBusquedaBlank;
    }

    public PatentData getPatentDataBlank() {
        return patentDataBlank;
    }

    // ── Acción principal: Carga Manual (Tab 2) ──────────────────────
    public void procesar() {
        FacesContext fc = FacesContext.getCurrentInstance();

        if (archivoFormulario == null || archivoFormulario.getSize() == 0) {
            fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error", "Seleccione el archivo de Formulario."));
            return;
        }
        if (archivoSegundoPdf == null || archivoSegundoPdf.getSize() == 0) {
            String label = "patente".equals(modo) ? "Reivindicaciones" : "Figuras";
            fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error", "Seleccione el archivo de " + label + "."));
            return;
        }

        File tempDir = null;
        try {
            tempDir = Files.createTempDirectory("senadi_pdf_").toFile();

            File formFile = new File(tempDir, "formulario.pdf");
            File secondFile = new File(tempDir, "segundo.pdf");
            IOUtils.copyStream(archivoFormulario.getInputStream(), formFile);
            IOUtils.copyStream(archivoSegundoPdf.getInputStream(), secondFile);

            File resultado;
            if ("patente".equals(modo)) {
                resultado = service.processPatent(formFile, secondFile, tempDir, ocultarContacto);
            } else {
                resultado = service.processDesign(formFile, secondFile, tempDir, ocultarContacto);
            }

            enviarPdfAlNavegador(fc, resultado);

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Error de procesamiento (archivos)", ex);
            fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error de procesamiento", ex.getMessage()));
        } finally {
            if (tempDir != null) {
                limpiarDirectorio(tempDir);
            }
        }
    }

    /**
     * Genera PDF a partir de los datos ingresados manualmente. Usa el mismo
     * pipeline que processPatentFromDB: genera página de datos con el banner,
     * misma tipografía y formato, e inserta reivindicaciones/figuras.
     */
    public void procesarManual() {
        FacesContext fc = FacesContext.getCurrentInstance();

        // Validar al menos título o número de solicitud
        if ((manTitulo == null || manTitulo.trim().isEmpty())
                && (manNumeroSolicitud == null || manNumeroSolicitud.trim().isEmpty())) {
            fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error", "Ingrese al menos el Título o el No. de Solicitud."));
            return;
        }

        File tempDir = null;
        try {
            tempDir = Files.createTempDirectory("senadi_manual_").toFile();

            // Construir PatentData desde los campos manuales
            PatentData pd = new PatentData();
            pd.setTipoPatente(manTipoPatente != null ? manTipoPatente.trim() : "Patente");
            pd.setApplicationNumber(manNumeroSolicitud != null ? manNumeroSolicitud.trim() : "");
            pd.setFechaSolicitud(manFechaSolicitud != null ? manFechaSolicitud.trim() : "");
            pd.setTitulo(manTitulo != null ? manTitulo.trim() : "");
            pd.setClasificacionInternacional(manClasificacion != null ? manClasificacion.trim() : "");
            pd.setResumen(manResumen != null ? manResumen.trim() : "");

            // Solicitante → como PersonaPatente
            if (manSolicitante != null && !manSolicitante.trim().isEmpty()) {
                PatentData.PersonaPatente sol = new PatentData.PersonaPatente();
                sol.setNombre(manSolicitante.trim());
                sol.setNacionalidad(manPais != null ? manPais.trim() : "");
                pd.getSolicitantes().add(sol);
            }

            // Representante
            if (manRepresentante != null && !manRepresentante.trim().isEmpty()) {
                PatentData.PersonaPatente rep = new PatentData.PersonaPatente();
                rep.setNombre(manRepresentante.trim());
                pd.setRepresentante(rep);
            }

            // Prioridad
            if (manFechaPrioridad != null && !manFechaPrioridad.trim().isEmpty()) {
                PatentData.Prioridad prio = new PatentData.Prioridad();
                prio.setFecha(manFechaPrioridad.trim());
                pd.getPrioridades().add(prio);
            }

            // Segundo PDF (reivindicaciones o figuras) — opcional
            File secondFile = null;
            if (manSegundoPdf != null && manSegundoPdf.getSize() > 0) {
                secondFile = new File(tempDir, "segundo.pdf");
                IOUtils.copyStream(manSegundoPdf.getInputStream(), secondFile);
            }

            // Determinar si es DI para elegir parámetro correcto
            boolean esDI = manTipoPatente != null
                    && manTipoPatente.toLowerCase().contains("dise");

            File reivPdf = esDI ? null : secondFile;
            File dibPdf = esDI ? secondFile : null;

            File resultado = service.processPatentFromDB(pd, reivPdf, dibPdf, tempDir);
            enviarPdfAlNavegador(fc, resultado);

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Error procesamiento manual", ex);
            fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error de procesamiento", ex.getMessage()));
        } finally {
            if (tempDir != null) {
                limpiarDirectorio(tempDir);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── BÚSQUEDA POR CÓDIGO (Tab 1) — NUEVO FLUJO CON DATOS DE BD ──
    // ══════════════════════════════════════════════════════════════════
    /**
     * Busca un trámite por código o ID en la base de datos MySQL. Cuando
     * encuentra una patente, carga automáticamente todos los datos (tipo,
     * título, solicitantes, países, resumen, etc.) desde la BD.
     */
    public void buscar() {
        FacesContext fc = FacesContext.getCurrentInstance();
        resultadosBusqueda = new ArrayList<>();
        resultadoSeleccionado = null;
        patentData = null;
        gazetteInfo = null;
        mensajeBusqueda = null;

        if (codigoBusqueda == null || codigoBusqueda.trim().isEmpty()) {
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                    "Atención", "Ingrese un número de trámite."));
            return;
        }

        String code = codigoBusqueda.trim().toUpperCase();

        try {
            buscando = true;
            resultadosBusqueda = dbService.search(code);

            if (resultadosBusqueda.isEmpty()) {
                mensajeBusqueda = "No se encontraron trámites para: " + code;
                fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                        "Sin resultados", mensajeBusqueda));
            } else if (resultadosBusqueda.size() == 1) {
                resultadoSeleccionado = resultadosBusqueda.get(0);

                // Cargar datos completos de la patente desde la BD
                LOG.info("isPatent=" + resultadoSeleccionado.isPatent()
                        + " formId=" + resultadoSeleccionado.getFormId()
                        + " formType=" + resultadoSeleccionado.getFormType());
                if (resultadoSeleccionado.isPatent()) {
                    patentData = dbService.loadPatentData(resultadoSeleccionado);
                    LOG.info("patentData cargada: " + (patentData != null
                            ? "titulo=" + patentData.getTitulo() + " solicitantes=" + patentData.getSolicitantesTexto()
                            : "** NULL **"));
                }

                String labelTipo = resultadoSeleccionado.getFormTypeLabel();
                if (patentData != null && patentData.getTipoPatente() != null
                        && !patentData.getTipoPatente().isEmpty()) {
                    labelTipo = patentData.getTipoPatente();
                }
                mensajeBusqueda = "Trámite encontrado: " + labelTipo;
                fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                        "Encontrado", mensajeBusqueda));

                // Verificar publicación previa en gaceta
                try {
                    gazetteInfo = dbService.findGazettePublication(
                            resultadoSeleccionado.getApplicationNumber());
                    if (gazetteInfo != null) {
                        fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                                "Gaceta", "PATENTE YA PUBLICADA EN GACETA N.\u00b0 " + gazetteInfo.getGazetteNumber()));
                    }
                } catch (Exception ex) {
                    LOG.warning("No se pudo verificar gaceta: " + ex.getMessage());
                }
            } else {
                mensajeBusqueda = "Se encontraron " + resultadosBusqueda.size()
                        + " trámites.";
                fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                        "Múltiples resultados", mensajeBusqueda));
            }
        } catch (Exception ex) {
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error de conexión", ex.getMessage()));
        } finally {
            buscando = false;
        }
    }

    public void limpiar() {
        FacesContext fc = FacesContext.getCurrentInstance();
        codigoBusqueda = "";
        patentData = null;
        gazetteInfo = null;
        fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                "INFORMACIÓN", "Contendor vaciado."));
    }
    
    public void limpiarBlank(){
        FacesContext fc = FacesContext.getCurrentInstance();
        codigoBusquedaBlank = "";
        patentDataBlank = null;        
        fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                "INFORMACIÓN", "Contendor vaciado."));
    }

    /**
     * Carga los datos de patente para un resultado específico cuando hay
     * múltiples resultados.
     */
    public void seleccionarResultado() {
        if (resultadoSeleccionado == null) {
            return;
        }
        gazetteInfo = null;
        try {
            if (resultadoSeleccionado.isPatent()) {
                patentData = dbService.loadPatentData(resultadoSeleccionado);
            } else {
                patentData = null;
            }
            // Verificar publicación previa en gaceta
            try {
                gazetteInfo = dbService.findGazettePublication(
                        resultadoSeleccionado.getApplicationNumber());
                if (gazetteInfo != null) {
                    FacesContext.getCurrentInstance().addMessage("searchMessages",
                            new FacesMessage(FacesMessage.SEVERITY_WARN,
                                    "Gaceta", "PATENTE YA PUBLICADA EN GACETA N.\u00b0 " + gazetteInfo.getGazetteNumber()));
                }
            } catch (Exception gex) {
                LOG.warning("No se pudo verificar gaceta: " + gex.getMessage());
            }
        } catch (Exception ex) {
            FacesContext.getCurrentInstance().addMessage("searchMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Error", "No se pudieron cargar los datos: " + ex.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── INICIALIZACIÓN: CARGAR LOTE PENDIENTE DESDE BD ──────────────
    // ══════════════════════════════════════════════════════════════════
    @PostConstruct
    public void init() {
        recargarLoteDesdeBD();
    }

    /**
     * Recarga el lote de pendientes desde la BD (tabloid_status = 1).
     */
    public void recargarLoteDesdeBD() {
        lotePat.clear();
        try {
            List<String> pendientes = dbService.loadPendingBatchApplicationNumbers();
            for (String appNum : pendientes) {
                try {
                    List<SearchResult> results = dbService.search(appNum);
                    if (!results.isEmpty()) {
                        PatentData pd = dbService.loadPatentData(results.get(0));
                        if (pd != null) {
                            BatchPatentItem item = new BatchPatentItem(pd);
                            if (!lotePat.contains(item)) {
                                lotePat.add(item);
                            }
                        }
                    }
                } catch (Exception innerEx) {
                    LOG.warning("No se pudo cargar datos del trámite pendiente " + appNum
                            + ": " + innerEx.getMessage());
                }
            }
            if (!lotePat.isEmpty()) {
                LOG.info("Lote recargado desde BD: " + lotePat.size() + " trámites");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error cargando lote pendiente desde BD", e);
        }
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc != null) {
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                    "Lote cargado", lotePat.size() + " trámite(s) pendiente(s) en el lote."));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── GESTIÓN DEL LOTE DE PATENTES ────────────────────────────────
    // ══════════════════════════════════════════════════════════════════
    /**
     * Agrega la patente actualmente cargada al lote (con verificación de
     * gaceta).
     */
    public void agregarAlLote() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (patentData == null) {
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                    "Atención", "Primero busque y seleccione una patente."));
            return;
        }
        BatchPatentItem item = new BatchPatentItem(patentData);
        if (lotePat.contains(item)) {
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                    "Duplicado", "La patente " + item.getApplicationNumber()
                    + " ya está cargada en el lote."));
            return;
        }

        // Verificar publicación previa en gacetas
        GazettePublicationInfo pubInfo = null;
        try {
            pubInfo = dbService.findGazettePublication(patentData.getApplicationNumber());
        } catch (Exception ex) {
            System.err.println("[WARN] No se pudo verificar publicación en gaceta: " + ex.getMessage());
        }

        if (pubInfo != null) {
            pendienteConfirmacion = item;
            pendienteConfirmacion.setPreviouslyPublished(true);
            PrimeFaces.current().executeScript("PF('dlgConfirmPublicado').show()");
            return;
        }

        lotePat.add(item);
        // Persistir en BD como pendiente (status=1)
        try {
            dbService.savePendingBatchItem(item.getApplicationNumber(), item.getFechaSolicitud());
        } catch (Exception dbEx) {
            LOG.log(Level.WARNING, "Error guardando trámite pendiente en BD: " + item.getApplicationNumber(), dbEx);
        }
        fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                "Agregada", item.getApplicationNumber() + " añadida al lote ("
                + lotePat.size() + " en total)."));
    }

    /**
     * Confirma agregar al lote una patente previamente publicada en gaceta.
     */
    public void confirmarAgregarAlLote() {
        if (pendienteConfirmacion == null) {
            return;
        }
        lotePat.add(pendienteConfirmacion);
        // Persistir en BD como pendiente (status=1)
        try {
            dbService.savePendingBatchItem(
                    pendienteConfirmacion.getApplicationNumber(),
                    pendienteConfirmacion.getFechaSolicitud());
        } catch (Exception dbEx) {
            LOG.log(Level.WARNING, "Error guardando trámite pendiente en BD: "
                    + pendienteConfirmacion.getApplicationNumber(), dbEx);
        }
        FacesContext.getCurrentInstance().addMessage("searchMessages",
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Agregada",
                        pendienteConfirmacion.getApplicationNumber()
                        + " añadida al lote (publicada anteriormente). ("
                        + lotePat.size() + " en total)."));
        pendienteConfirmacion = null;
    }

    /**
     * Cancela agregar al lote una patente previamente publicada.
     */
    public void cancelarAgregarAlLote() {
        if (pendienteConfirmacion != null) {
            FacesContext.getCurrentInstance().addMessage("searchMessages",
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Cancelado",
                            "No se agregó " + pendienteConfirmacion.getApplicationNumber()
                            + " al lote."));
            pendienteConfirmacion = null;
        }
    }

    public BatchPatentItem getPendienteConfirmacion() {
        return pendienteConfirmacion;
    }

    /**
     * Prepara la eliminación de un elemento del lote (muestra diálogo).
     */
    public void prepararEliminarDelLote(int index) {
        indicePendienteEliminar = index;
    }

    /**
     * Confirma la eliminación del elemento del lote (lista + BD).
     */
    public void confirmarEliminarDelLote() {
        if (indicePendienteEliminar >= 0 && indicePendienteEliminar < lotePat.size()) {
            BatchPatentItem removed = lotePat.remove(indicePendienteEliminar);
            try {
                dbService.deletePendingBatchItem(removed.getApplicationNumber());
            } catch (Exception dbEx) {
                LOG.log(Level.WARNING, "Error eliminando trámite pendiente de BD: "
                        + removed.getApplicationNumber(), dbEx);
            }
            FacesContext.getCurrentInstance().addMessage("searchMessages",
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Eliminada",
                            removed.getApplicationNumber() + " quitada del lote."));
        }
        indicePendienteEliminar = -1;
    }

    /**
     * Nombre del trámite pendiente de eliminación (para el diálogo).
     */
    public String getNombrePendienteEliminar() {
        if (indicePendienteEliminar >= 0 && indicePendienteEliminar < lotePat.size()) {
            return lotePat.get(indicePendienteEliminar).getApplicationNumber();
        }
        return "";
    }

    /**
     * Vacía la lista de lote completa (lista + BD).
     */
    public void vaciarLote() {
        try {
            dbService.deleteAllPendingBatchItems();
        } catch (Exception dbEx) {
            LOG.log(Level.WARNING, "Error vaciando lote pendiente de BD", dbEx);
        }
        lotePat.clear();
        FacesContext.getCurrentInstance().addMessage("searchMessages",
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Lote vaciado",
                        "Se eliminaron todas las patentes del lote."));
    }

    /**
     * Mueve un elemento una posición arriba en la lista.
     */
    public void moverArriba(int index) {
        if (index > 0 && index < lotePat.size()) {
            BatchPatentItem item = lotePat.remove(index);
            lotePat.add(index - 1, item);
        }
    }

    /**
     * Mueve un elemento una posición abajo en la lista.
     */
    public void moverAbajo(int index) {
        if (index >= 0 && index < lotePat.size() - 1) {
            BatchPatentItem item = lotePat.remove(index);
            lotePat.add(index + 1, item);
        }
    }

    /**
     * Genera un solo PDF unificado con todas las patentes del lote en
     * composición continua (sin desperdiciar páginas).
     */
    public void generarPdfLote() {
        FacesContext fc = FacesContext.getCurrentInstance();

        if (lotePat.isEmpty()) {
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                    "Lote vacío", "Agregue al menos una patente al lote."));
            return;
        }

        File tempDir = null;
        try {
            tempDir = Files.createTempDirectory("senadi_batch_").toFile();
            List<PatentData> dataList = new ArrayList<>();
            List<File> reivFiles = new ArrayList<>();
            List<File> dibFiles = new ArrayList<>();

            // Cargar datos y archivos de cada patente
            for (int i = 0; i < lotePat.size(); i++) {
                BatchPatentItem item = lotePat.get(i);
                LOG.info("generarPdfLote: cargando patente " + (i + 1) + "/" + lotePat.size()
                        + " — " + item.getApplicationNumber());

                // Buscar en BD y cargar PatentData
                List<SearchResult> results = dbService.search(item.getApplicationNumber());
                SearchResult sr = null;
                for (SearchResult r : results) {
                    if (r.getFormId() == item.getFormId()) {
                        sr = r;
                        break;
                    }
                }
                if (sr == null && !results.isEmpty()) {
                    sr = results.get(0);
                }
                if (sr == null) {
                    fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Error", "No se encontró la patente " + item.getApplicationNumber()));
                    return;
                }

                PatentData pd = dbService.loadPatentData(sr);
                if (pd == null) {
                    fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Error", "No se pudieron cargar datos de " + item.getApplicationNumber()));
                    return;
                }
                dataList.add(pd);

                // Auto-detectar por tipo de patente (misma lógica que flujo individual)
                boolean esDI = pd.isDisenoIndustrial();
                boolean cargarReiv = !esDI;
                boolean cargarDib = esDI;
                LOG.info("generarPdfLote: " + pd.getApplicationNumber()
                        + " patentTypeId=" + pd.getPatentTypeId()
                        + " isDI=" + esDI + " cargarReiv=" + cargarReiv
                        + " cargarDib=" + cargarDib);

                File reivFile = null;
                if (cargarReiv) {
                    String reivFileName = pd.getReivindicacionesFile();
                    reivFile = obtenerArchivo(reivFileName, REIVINDICACIONES_DIR);
                    if (reivFile == null && reivFileName != null && !reivFileName.isEmpty() && pd.getFormId() > 0) {
                        reivFile = downloadFromPublicUrl(pd.getFormId(), reivFileName, tempDir);
                    }
                    if (reivFile != null) {
                        byte[] header = new byte[5];
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(reivFile)) {
                            fis.read(header);
                        }
                        if (header[0] != '%' || header[1] != 'P' || header[2] != 'D' || header[3] != 'F') {
                            reivFile = null;
                        } else {
                            guardarCopiaLocalSiNecesario(reivFile, reivFileName, REIVINDICACIONES_DIR);
                        }
                    }
                }
                reivFiles.add(reivFile);

                File dibFile = null;
                if (cargarDib) {
                    String dibFileName = pd.getDibujosFile();
                    dibFile = obtenerArchivo(dibFileName, DIBUJOS_DIR);
                    if (dibFile == null && dibFileName != null && !dibFileName.isEmpty() && pd.getFormId() > 0) {
                        dibFile = downloadFromPublicUrl(pd.getFormId(), dibFileName, tempDir);
                    }
                    if (dibFile != null) {
                        byte[] header = new byte[5];
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(dibFile)) {
                            fis.read(header);
                        }
                        if (header[0] != '%' || header[1] != 'P' || header[2] != 'D' || header[3] != 'F') {
                            dibFile = null;
                        } else {
                            guardarCopiaLocalSiNecesario(dibFile, dibFileName, DIBUJOS_DIR);
                        }
                    }
                }
                dibFiles.add(dibFile);
            }

            // Generar PDF por lote continuo (auto-detecta reiv/dib por patente)
            File resultado;
            String downloadName;
            resultado = service.processPatentBatchFromDB(dataList, reivFiles, dibFiles, tempDir);
            downloadName = "LOTE_PATENTES_" + lotePat.size() + ".pdf";

            enviarPdfAlNavegador(fc, resultado, downloadName);

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Error generando PDF por lote", ex);
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error de procesamiento", ex.getMessage()));
        } finally {
            if (tempDir != null) {
                limpiarDirectorio(tempDir);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ── LOTE "DATOS CON HOJAS EN BLANCO" — TEMPORAL, SIN BD ────────
    // ══════════════════════════════════════════════════════════════════
    /**
     * Busca un trámite para el apartado de datos con hojas en blanco.
     */
    public void buscarBlank() {
        FacesContext fc = FacesContext.getCurrentInstance();
        resultadosBusquedaBlank = new ArrayList<>();
        resultadoSeleccionadoBlank = null;
        patentDataBlank = null;
        mensajeBusquedaBlank = null;

        if (codigoBusquedaBlank == null || codigoBusquedaBlank.trim().isEmpty()) {
            fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                    "Atención", "Ingrese un número de trámite."));
            return;
        }

        String code = codigoBusquedaBlank.trim().toUpperCase();
        try {
            resultadosBusquedaBlank = dbService.search(code);
            if (resultadosBusquedaBlank.isEmpty()) {
                mensajeBusquedaBlank = "No se encontraron trámites para: " + code;
                fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                        "Sin resultados", mensajeBusquedaBlank));
            } else if (resultadosBusquedaBlank.size() == 1) {
                resultadoSeleccionadoBlank = resultadosBusquedaBlank.get(0);
                if (resultadoSeleccionadoBlank.isPatent()) {
                    patentDataBlank = dbService.loadPatentData(resultadoSeleccionadoBlank);
                }
                mensajeBusquedaBlank = "Trámite encontrado";
                fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                        "Encontrado", mensajeBusquedaBlank));
            } else {
                mensajeBusquedaBlank = "Se encontraron " + resultadosBusquedaBlank.size() + " trámites.";
                fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                        "Múltiples resultados", mensajeBusquedaBlank));
            }
        } catch (Exception ex) {
            fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error de conexión", ex.getMessage()));
        }
    }

    /**
     * Selecciona un resultado específico en la búsqueda del lote blank.
     */
    public void seleccionarResultadoBlank() {
        if (resultadoSeleccionadoBlank == null) {
            return;
        }
        try {
            if (resultadoSeleccionadoBlank.isPatent()) {
                patentDataBlank = dbService.loadPatentData(resultadoSeleccionadoBlank);
            } else {
                patentDataBlank = null;
            }
        } catch (Exception ex) {
            FacesContext.getCurrentInstance().addMessage("blankMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Error", "No se pudieron cargar los datos: " + ex.getMessage()));
        }
    }

    /**
     * Agrega la patente buscada al lote de hojas en blanco (sin BD).
     */
    public void agregarAlLoteBlank() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (patentDataBlank == null) {
            fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                    "Atención", "Primero busque y seleccione una patente."));
            return;
        }
        BatchPatentItem item = new BatchPatentItem(patentDataBlank);
        if (loteBlank.contains(item)) {
            fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                    "Duplicado", "La patente " + item.getApplicationNumber()
                    + " ya está en el lote."));
            return;
        }
        loteBlank.add(item);
        fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_INFO,
                "Agregada", item.getApplicationNumber() + " añadida al lote ("
                + loteBlank.size() + " en total)."));
    }

    /**
     * Elimina un item del lote blank por índice.
     */
    public void eliminarDelLoteBlank(int index) {
        if (index >= 0 && index < loteBlank.size()) {
            BatchPatentItem removed = loteBlank.remove(index);
            FacesContext.getCurrentInstance().addMessage("blankMessages",
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Eliminada",
                            removed.getApplicationNumber() + " quitada del lote."));
        }
    }

    /**
     * Mueve un elemento una posición arriba en el lote blank.
     */
    public void moverArribaBlank(int index) {
        if (index > 0 && index < loteBlank.size()) {
            BatchPatentItem item = loteBlank.remove(index);
            loteBlank.add(index - 1, item);
        }
    }

    /**
     * Mueve un elemento una posición abajo en el lote blank.
     */
    public void moverAbajoBlank(int index) {
        if (index >= 0 && index < loteBlank.size() - 1) {
            BatchPatentItem item = loteBlank.remove(index);
            loteBlank.add(index + 1, item);
        }
    }

    /**
     * Vacía el lote blank (solo memoria, no toca BD).
     */
    public void vaciarLoteBlank() {
        loteBlank.clear();
        FacesContext.getCurrentInstance().addMessage("blankMessages",
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Lote vaciado",
                        "Se eliminaron todas las patentes del lote."));
    }

    /**
     * Genera el PDF del lote "Datos con hojas en blanco": datos + banner +
     * espacio vacío + hoja en blanco por cada patente.
     */
    public void generarPdfLoteBlank() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (loteBlank.isEmpty()) {
            fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                    "Lote vacío", "Agregue al menos una patente al lote."));
            return;
        }

        File tempDir = null;
        try {
            tempDir = Files.createTempDirectory("senadi_blank_").toFile();
            List<PatentData> dataList = new ArrayList<>();

            for (BatchPatentItem item : loteBlank) {
                List<SearchResult> results = dbService.search(item.getApplicationNumber());
                SearchResult sr = null;
                for (SearchResult r : results) {
                    if (r.getFormId() == item.getFormId()) {
                        sr = r;
                        break;
                    }
                }
                if (sr == null && !results.isEmpty()) {
                    sr = results.get(0);
                }
                if (sr == null) {
                    fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Error", "No se encontró la patente " + item.getApplicationNumber()));
                    return;
                }
                PatentData pd = dbService.loadPatentData(sr);
                if (pd == null) {
                    fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Error", "No se pudieron cargar datos de " + item.getApplicationNumber()));
                    return;
                }
                dataList.add(pd);
            }

            File resultado = service.processBlankSheetBatch(dataList, tempDir);
            String downloadName = "LOTE_DATOS_BLANCO_" + loteBlank.size() + ".pdf";
            enviarPdfAlNavegador(fc, resultado, downloadName);

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Error generando PDF lote blank", ex);
            fc.addMessage("blankMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error de procesamiento", ex.getMessage()));
        } finally {
            if (tempDir != null) {
                limpiarDirectorio(tempDir);
            }
        }
    }

    // ── GENERAR PDF DESDE DATOS DE LA BD ────────────────────────────
    /**
     * Genera el PDF final usando datos de la BD + reivindicaciones: 1) Primera
     * página generada con datos del PatentData 2) Segunda página con la primera
     * reivindicación
     */
    public void generarPdfDesdeBase() {
        FacesContext fc = FacesContext.getCurrentInstance();

        if (patentData == null) {
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error", "No hay datos de patente cargados."));
            return;
        }

        File tempDir = null;
        try {
            tempDir = Files.createTempDirectory("senadi_base_").toFile();
            File resultado;

            // Determinar qué anexos cargar según el tipo de patente
            boolean esDisenoIndustrial = patentData.isDisenoIndustrial();
            LOG.info("generarPdfDesdeBase: patentTypeId=" + patentData.getPatentTypeId()
                    + " tipo=" + patentData.getPatentType()
                    + " esDisenoIndustrial=" + esDisenoIndustrial);

            // ── Reivindicaciones (solo para PI, MU, PC — NO para DI) ──
            File reivFile = null;
            if (!esDisenoIndustrial) {
                // Intentar obtener archivo de reivindicaciones
                String reivFileName = patentData.getReivindicacionesFile();
                LOG.info("generarPdfDesdeBase: reivFileName=" + reivFileName
                        + " formId=" + patentData.getFormId());
                reivFile = obtenerArchivo(reivFileName, REIVINDICACIONES_DIR);
                LOG.info("generarPdfDesdeBase: obtenerArchivo result=" + (reivFile != null ? reivFile.getAbsolutePath() : "null"));

                // Fallback: descargar desde la URL pública del registro
                if (reivFile == null && reivFileName != null && !reivFileName.isEmpty() && patentData.getFormId() > 0) {
                    LOG.info("Descargando reivindicaciones desde URL pública...");
                    reivFile = downloadFromPublicUrl(patentData.getFormId(), reivFileName, tempDir);
                    LOG.info("generarPdfDesdeBase: downloadFromPublicUrl result=" + (reivFile != null ? reivFile.getAbsolutePath() : "null"));
                }

                if (reivFile != null) {
                    // Validar que el archivo local sea un PDF real
                    byte[] header = new byte[5];
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(reivFile)) {
                        fis.read(header);
                    }
                    if (header[0] != '%' || header[1] != 'P' || header[2] != 'D' || header[3] != 'F') {
                        LOG.warning("Archivo de reivindicaciones NO es PDF válido: " + reivFile.getAbsolutePath());
                        reivFile.delete(); // Borrar copia local inválida
                        reivFile = null;
                    } else {
                        // Guardar copia local si aún no está en el directorio de caché
                        guardarCopiaLocalSiNecesario(reivFile, reivFileName, REIVINDICACIONES_DIR);
                    }
                }
            }

            // ── Dibujos (solo para DI – Diseños Industriales) ──
            File dibFile = null;
            if (esDisenoIndustrial) {
                String dibFileName = patentData.getDibujosFile();
                LOG.info("generarPdfDesdeBase: dibFileName=" + dibFileName);
                dibFile = obtenerArchivo(dibFileName, DIBUJOS_DIR);

                if (dibFile == null && dibFileName != null && !dibFileName.isEmpty() && patentData.getFormId() > 0) {
                    LOG.info("Descargando dibujos desde URL pública...");
                    dibFile = downloadFromPublicUrl(patentData.getFormId(), dibFileName, tempDir);
                }

                // Validar que el archivo de dibujos sea PDF real
                if (dibFile != null) {
                    byte[] dibHeader = new byte[5];
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(dibFile)) {
                        fis.read(dibHeader);
                    }
                    if (dibHeader[0] != '%' || dibHeader[1] != 'P' || dibHeader[2] != 'D' || dibHeader[3] != 'F') {
                        LOG.warning("Archivo de dibujos NO es PDF válido: " + dibFile.getAbsolutePath());
                        dibFile.delete();
                        dibFile = null;
                    } else {
                        // Guardar copia local si vino de URL
                        guardarCopiaLocalSiNecesario(dibFile, dibFileName, DIBUJOS_DIR);
                    }
                }
            }
            LOG.info("generarPdfDesdeBase: reivFile=" + (reivFile != null ? reivFile.getAbsolutePath() : "null")
                    + " dibFile=" + (dibFile != null ? dibFile.getAbsolutePath() : "null"));

            // ── Generar PDF final ──
            if (reivFile != null || dibFile != null) {
                LOG.info("generarPdfDesdeBase: Procesando PDF combinado"
                        + " reiv=" + (reivFile != null) + " dib=" + (dibFile != null));
                resultado = service.processPatentFromDB(patentData, reivFile, dibFile, tempDir);
                LOG.info("generarPdfDesdeBase: PDF combinado generado: " + resultado.getAbsolutePath()
                        + " (" + resultado.length() + " bytes)");
            } else {
                // Sin archivo requerido: solo la página de datos
                resultado = service.generateDataPageOnly(patentData, tempDir);
                LOG.info("generarPdfDesdeBase: Solo página de datos: " + resultado.getAbsolutePath()
                        + " (" + resultado.length() + " bytes)");
                String aviso = esDisenoIndustrial
                        ? "No se encontró archivo de DIBUJOS para este Diseño Industrial."
                        : "No se encontró archivo de REIVINDICACIONES para esta patente.";
                fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_WARN,
                        "Aviso", aviso + " Se generó solo la página de datos."));
            }

            String downloadName = "PATENTE_" + patentData.getApplicationNumber() + ".pdf";
            LOG.info("generarPdfDesdeBase: Enviando al navegador: " + downloadName);
            enviarPdfAlNavegador(fc, resultado, downloadName);

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Error de procesamiento (BD)", ex);
            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error de procesamiento", ex.getMessage()));
        } finally {
            if (tempDir != null) {
                limpiarDirectorio(tempDir);
            }
        }
    }

    /**
     * Descarga las reivindicaciones encontradas. Primero local, luego servidor.
     */
    public void descargarReivindicaciones() {
        if (patentData == null || patentData.getReivindicacionesFile() == null) {
            return;
        }
        FacesContext fc = FacesContext.getCurrentInstance();
        String fileName = patentData.getReivindicacionesFile();
        String downloadName = "REIVINDICACIONES_" + patentData.getApplicationNumber() + ".pdf";

        try {
            File local = findLocalFile(fileName, REIVINDICACIONES_DIR);
            if (local != null) {
                enviarPdfAlNavegador(fc, local, downloadName);
                return;
            }

            byte[] data = dbService.downloadFileFromServer(fileName);
            if (data != null) {
                guardarArchivoLocal(data, fileName, REIVINDICACIONES_DIR);
                enviarBytesAlNavegador(fc, data, downloadName);
                return;
            }

            fc.addMessage("searchMessages", new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "No disponible",
                    "No se pudo descargar " + fileName + " del servidor."));
        } catch (IOException ex) {
            fc.addMessage("searchMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", ex.getMessage()));
        }
    }

    // ── Utilidades ──────────────────────────────────────────────────
    private File findLocalFile(String fileName, String directory) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        File dir = new File(directory);
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }

        File exact = new File(dir, fileName);
        if (exact.exists()) {
            return exact;
        }

        String baseName = fileName;
        int dotIdx = baseName.lastIndexOf('.');
        if (dotIdx > 0) {
            baseName = baseName.substring(0, dotIdx);
        }

        File[] matches = dir.listFiles();
        if (matches != null) {
            for (File f : matches) {
                if (f.getName().contains(baseName)) {
                    return f;
                }
            }
        }
        return null;
    }

    private File obtenerArchivo(String fileName, String directory) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        File local = findLocalFile(fileName, directory);
        if (local != null) {
            return local;
        }

        byte[] data = dbService.downloadFileFromServer(fileName);
        if (data != null) {
            File saved = guardarArchivoLocal(data, fileName, directory);
            if (saved != null) {
                return saved;
            }
        }
        return null;
    }

    /**
     * Descarga un archivo PDF desde la URL pública del registro de propiedad
     * intelectual. URL:
     * https://registro.propiedadintelectual.gob.ec/solicitudes/media/files/patent_forms/{formId}/{fileName}
     */
    private File downloadFromPublicUrl(int formId, String fileName, File destDir) {
        String urlStr = "https://registro.propiedadintelectual.gob.ec/solicitudes/media/files/patent_forms/"
                + formId + "/" + fileName;
        LOG.info("Descargando desde: " + urlStr);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "SENADI-PdfTool/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                LOG.warning("HTTP " + code + " al descargar " + urlStr);
                return null;
            }

            // Leer el contenido en memoria primero
            byte[] content;
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                content = baos.toByteArray();
            }

            // Validar que sea un PDF real (debe empezar con %PDF)
            if (content.length < 5 || content[0] != '%' || content[1] != 'P'
                    || content[2] != 'D' || content[3] != 'F') {
                LOG.warning("El archivo descargado NO es un PDF válido (" + content.length
                        + " bytes). Probablemente es una página HTML de error.");
                return null;
            }

            File output = new File(destDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(output)) {
                fos.write(content);
            }

            LOG.info("Descargado exitosamente: " + output.getAbsolutePath() + " (" + output.length() + " bytes)");

            return output;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error al descargar desde URL pública: " + e.getMessage(), e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private File guardarArchivoLocal(byte[] data, String fileName, String directory) {
        try {
            File dir = new File(directory);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
                fos.flush();
            }
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    private void enviarPdfAlNavegador(FacesContext fc, File pdfFile) throws IOException {
        enviarPdfAlNavegador(fc, pdfFile, "RESULTADO_FINAL.pdf");
    }

    /**
     * Guarda una copia local del archivo si no está ya en el directorio de
     * caché
     */
    private void guardarCopiaLocalSiNecesario(File file, String fileName, String cacheDir) {
        try {
            File dir = new File(cacheDir);
            File cached = new File(dir, fileName);
            if (!cached.exists()) {
                guardarArchivoLocal(java.nio.file.Files.readAllBytes(file.toPath()), fileName, cacheDir);
            }
        } catch (IOException e) {
            LOG.fine("No se pudo guardar copia local: " + e.getMessage());
        }
    }

    private void enviarPdfAlNavegador(FacesContext fc, File pdfFile, String downloadName)
            throws IOException {
        ExternalContext ec = fc.getExternalContext();
        ec.responseReset();
        ec.setResponseContentType("application/pdf");
        ec.setResponseContentLength((int) pdfFile.length());
        ec.setResponseHeader("Content-Disposition",
                "attachment; filename=\"" + downloadName + "\"");

        try (InputStream in = Files.newInputStream(pdfFile.toPath()); OutputStream out = ec.getResponseOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        }
        fc.responseComplete();
    }

    private void enviarBytesAlNavegador(FacesContext fc, byte[] data, String downloadName)
            throws IOException {
        ExternalContext ec = fc.getExternalContext();
        ec.responseReset();
        ec.setResponseContentType("application/pdf");
        ec.setResponseContentLength(data.length);
        ec.setResponseHeader("Content-Disposition",
                "attachment; filename=\"" + downloadName + "\"");

        try (OutputStream out = ec.getResponseOutputStream()) {
            out.write(data);
            out.flush();
        }
        fc.responseComplete();
    }

    private void limpiarDirectorio(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    // ══════════════════════════════════════════════════════════════════
    // ── ADMINISTRACIÓN — GUARDAR EN GACETA (solo admin) ─────────────
    // ══════════════════════════════════════════════════════════════════
    public String getNumeroGacetaGuardar() {
        return numeroGacetaGuardar;
    }

    public void setNumeroGacetaGuardar(String v) {
        this.numeroGacetaGuardar = v;
    }

    /**
     * Confirma la publicación del lote: actualiza tabloid_status de '1' a '0' y
     * asigna el número de gaceta. Solo disponible para administradores.
     */
    public void guardarEnGaceta() {
        FacesContext fc = FacesContext.getCurrentInstance();

        if (authBean == null || !authBean.isAdmin()) {
            fc.addMessage("adminMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Error", "No tiene permisos para esta acción."));
            return;
        }

        if (numeroGacetaGuardar == null || numeroGacetaGuardar.trim().isEmpty()) {
            fc.addMessage("adminMessages",
                    new FacesMessage(FacesMessage.SEVERITY_WARN,
                            "Atención", "Ingrese el número de gaceta."));
            return;
        }

        if (lotePat.isEmpty()) {
            fc.addMessage("adminMessages",
                    new FacesMessage(FacesMessage.SEVERITY_WARN,
                            "Atención", "No hay trámites pendientes en el lote."));
            return;
        }

        String gazetteNum = numeroGacetaGuardar.trim();
        try {
            int count = dbService.confirmPendingBatch(gazetteNum);
            lotePat.clear();
            fc.addMessage("adminMessages",
                    new FacesMessage(FacesMessage.SEVERITY_INFO,
                            "Publicación confirmada",
                            count + " registro(s) confirmado(s) en gaceta N.\u00b0 " + gazetteNum));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error confirmando publicación en gaceta", e);
            fc.addMessage("adminMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Error", "No se pudo confirmar la publicación: " + e.getMessage()));
        }
    }
}
