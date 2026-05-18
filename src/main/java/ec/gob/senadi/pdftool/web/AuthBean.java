package ec.gob.senadi.pdftool.web;

import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.context.SessionScoped;
import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.inject.Named;

import ec.gaceta.security.LDAP;

@Named("authBean")
@SessionScoped
public class AuthBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(AuthBean.class.getName());

    private String username;
    private String password;
    private String role;        // "ADMIN" o "USER"
    private String displayName;

    private static final String GROUP_ADMIN = "SC_PatgacetaAdmin";
    private static final String GROUP_USER  = "SC_Patgaceta";

    /**
     * Modo desarrollo: cuando LDAP no está accesible,
     * permite login con credenciales locales de prueba.
     * Poner en false para producción.
     */
    private static final boolean DEV_MODE = true;

    // ── Login ──────────────────────────────────────────────

    public String login() {
        FacesContext fc = FacesContext.getCurrentInstance();

        if (username == null || username.trim().isEmpty()
                || password == null || password.trim().isEmpty()) {
            fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error", "Ingrese usuario y contraseña."));
            return null;
        }

        String user = username.trim();

        // ── Modo desarrollo (credenciales locales) ──
        if (DEV_MODE) {
            if ("admin".equalsIgnoreCase(user) && "admin".equals(password)) {
                role = "ADMIN";
                displayName = user;
                password = null;
                fc.getExternalContext().getSessionMap().put("USER_LOGGED_IN", Boolean.TRUE);
                LOG.info("[DEV] Login exitoso (ADMIN): " + user);
                return "index.xhtml?faces-redirect=true";
            }
            if ("user".equalsIgnoreCase(user) && "user".equals(password)) {
                role = "USER";
                displayName = user;
                password = null;
                fc.getExternalContext().getSessionMap().put("USER_LOGGED_IN", Boolean.TRUE);
                LOG.info("[DEV] Login exitoso (USER): " + user);
                return "index.xhtml?faces-redirect=true";
            }
        }

        try {
            LDAP ldap = new LDAP();

            // 1) Intentar grupo Administrador
            int result = ldap.validarIngresoLDAPRestringido(user, password, GROUP_ADMIN);

            if (result == 1) {
                // Autenticado como admin
                role = "ADMIN";
                displayName = user;
                password = null;
                fc.getExternalContext().getSessionMap().put("USER_LOGGED_IN", Boolean.TRUE);
                LOG.info("Login exitoso (ADMIN): " + user);
                return "index.xhtml?faces-redirect=true";
            }

            if (result == 0) {
                // Credenciales incorrectas — no intentar segundo grupo
                fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                        "Error", "Credenciales incorrectas."));
                password = null;
                return null;
            }

            // result == -1 → autenticado pero no está en grupo admin → probar grupo usuario
            result = ldap.validarIngresoLDAPRestringido(user, password, GROUP_USER);

            if (result == 1) {
                role = "USER";
                displayName = user;
                password = null;
                fc.getExternalContext().getSessionMap().put("USER_LOGGED_IN", Boolean.TRUE);
                LOG.info("Login exitoso (USER): " + user);
                return "index.xhtml?faces-redirect=true";
            }

            if (result == 0) {
                fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                        "Error", "Credenciales incorrectas."));
            } else {
                // -1 en ambos grupos → no tiene permiso
                fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                        "Acceso denegado",
                        "No tiene permisos para acceder a esta aplicación."));
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error durante autenticación LDAP", e);
            fc.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error de conexión",
                    "No se pudo conectar al servidor de autenticación. Intente más tarde."));
        }

        password = null;
        return null;
    }

    // ── Logout ─────────────────────────────────────────────

    public String logout() {
        LOG.info("Logout: " + displayName);
        FacesContext.getCurrentInstance().getExternalContext().invalidateSession();
        return "login.xhtml?faces-redirect=true";
    }

    // ── Consultas de rol ───────────────────────────────────

    public boolean isLoggedIn() {
        return role != null;
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public String getRoleLabel() {
        if ("ADMIN".equals(role)) return "Administrador";
        if ("USER".equals(role))  return "Usuario";
        return "";
    }

    // ── Getters / Setters ──────────────────────────────────

    public String getUsername()    { return username; }
    public void setUsername(String v) { this.username = v; }

    public String getPassword()    { return password; }
    public void setPassword(String v) { this.password = v; }

    public String getRole()        { return role; }
    public String getDisplayName() { return displayName; }
}
