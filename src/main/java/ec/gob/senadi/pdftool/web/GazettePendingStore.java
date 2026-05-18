package ec.gob.senadi.pdftool.web;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import ec.gob.senadi.pdftool.model.BatchPatentItem;
import ec.gob.senadi.pdftool.model.GazettePendingRecord;
import ec.gob.senadi.pdftool.model.PatentData;

/**
 * Almacén en memoria (application-scope) de registros pendientes
 * de publicación en gaceta. Todos los usuarios comparten esta lista
 * y solo los administradores pueden guardarla en base de datos.
 */
@Named("pendingStore")
@ApplicationScoped
public class GazettePendingStore implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<GazettePendingRecord> items =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * Registra los ítems de un lote como pendientes de publicación.
     * Evita duplicados por applicationNumber.
     */
    public void addBatch(String username, List<BatchPatentItem> batchItems,
                         List<PatentData> dataList) {
        String timestamp = LocalDateTime.now().format(FMT);

        synchronized (items) {
            for (int i = 0; i < batchItems.size(); i++) {
                BatchPatentItem bi = batchItems.get(i);
                // Evitar duplicados
                boolean exists = items.stream()
                        .anyMatch(r -> r.getApplicationNumber() != null
                                && r.getApplicationNumber()
                                     .equalsIgnoreCase(bi.getApplicationNumber()));
                if (exists) continue;

                GazettePendingRecord rec = new GazettePendingRecord();
                rec.setApplicationNumber(bi.getApplicationNumber());
                rec.setTitulo(bi.getTitulo());
                rec.setTipoPatente(bi.getTipoPatente());
                rec.setRegistradoPor(username);
                rec.setFechaRegistro(timestamp);

                // fechaPatente desde PatentData o desde BatchPatentItem
                if (i < dataList.size() && dataList.get(i) != null) {
                    rec.setFechaPatente(dataList.get(i).getFechaSolicitud());
                } else {
                    rec.setFechaPatente(bi.getFechaSolicitud());
                }

                items.add(rec);
            }
        }
    }

    /** Todos los items (para la tabla de admin). */
    public List<GazettePendingRecord> getPendingItems() {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    /** Solo los items que aún no han sido guardados. */
    public List<GazettePendingRecord> getUnsavedItems() {
        synchronized (items) {
            List<GazettePendingRecord> result = new ArrayList<>();
            for (GazettePendingRecord r : items) {
                if (!r.isSavedToDb()) result.add(r);
            }
            return result;
        }
    }

    /** Marca un item como guardado en BD. */
    public void markSaved(String applicationNumber) {
        synchronized (items) {
            for (GazettePendingRecord r : items) {
                if (r.getApplicationNumber() != null
                        && r.getApplicationNumber().equalsIgnoreCase(applicationNumber)) {
                    r.setSavedToDb(true);
                    break;
                }
            }
        }
    }

    /** Elimina los items ya guardados de la lista. */
    public void removeSaved() {
        synchronized (items) {
            items.removeIf(GazettePendingRecord::isSavedToDb);
        }
    }

    /** Cantidad de items pendientes (no guardados). */
    public int getUnsavedCount() {
        synchronized (items) {
            int count = 0;
            for (GazettePendingRecord r : items) {
                if (!r.isSavedToDb()) count++;
            }
            return count;
        }
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
