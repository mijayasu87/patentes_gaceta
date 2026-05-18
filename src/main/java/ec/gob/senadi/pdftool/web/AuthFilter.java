package ec.gob.senadi.pdftool.web;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Filtro de autenticación que protege todas las páginas .xhtml
 * excepto login.xhtml y los recursos de JSF/PrimeFaces.
 */
public class AuthFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        // Evitar cache de páginas HTML (previene ViewState obsoleto tras logout)
        res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        res.setHeader("Pragma", "no-cache");
        res.setDateHeader("Expires", 0);

        String uri = req.getRequestURI();

        // Permitir login.xhtml y recursos estáticos de JSF / PrimeFaces
        if (uri.endsWith("/login.xhtml")
                || uri.contains("/javax.faces.resource/")) {
            chain.doFilter(request, response);
            return;
        }

        // Verificar sesión autenticada
        HttpSession session = req.getSession(false);
        boolean loggedIn = session != null
                && Boolean.TRUE.equals(session.getAttribute("USER_LOGGED_IN"));

        if (loggedIn) {
            chain.doFilter(request, response);
            return;
        }

        // No autenticado → redirigir a login
        String loginUrl = req.getContextPath() + "/login.xhtml";

        // Si es petición AJAX de JSF, responder con redirect XML
        if ("partial/ajax".equals(req.getHeader("Faces-Request"))) {
            res.setContentType("text/xml");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<partial-response><redirect url=\""
                    + loginUrl + "\"></redirect></partial-response>");
        } else {
            res.sendRedirect(loginUrl);
        }
    }

    @Override
    public void destroy() {
        // no-op
    }
}
