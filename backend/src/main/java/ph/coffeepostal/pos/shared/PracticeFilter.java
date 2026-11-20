package ph.coffeepostal.pos.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Practice mode is a flag on the signed-in session. While it is on, the request runs against the practice tables, and anything
 * that would change data outside the practice flow (a sale, an expense, a shift...) is refused, so practice can never write real data
 * by accident. Reading is always allowed.
 */
public final class PracticeFilter extends OncePerRequestFilter {
    public static final String ATTR = "cp.practice";

    /** The only places a practice session may change data: the purchase order flow, its invoices, and the practice controls. */
    private static final List<String> MAY_CHANGE = List.of("/api/practice", "/api/branch/purchase-orders", "/api/admin/branch-pos",
        "/api/branch/payables", "/api/admin/receivables", "/api/auth/logout");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        HttpSession s = req.getSession(false);
        boolean on = s != null && Boolean.TRUE.equals(s.getAttribute(ATTR));
        if (!on) {
            chain.doFilter(req, res);
            return;
        }
        String m = req.getMethod();
        boolean read = m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS");
        String path = req.getRequestURI();
        if (!read && MAY_CHANGE.stream().noneMatch((p) -> path.equals(p) || path.startsWith(p + "/"))) {
            res.setStatus(409);
            res.setContentType("application/json");
            res.getWriter().write("{\"message\":\"You are in practice mode. Leave practice mode to do this for real.\",\"fields\":{}}");
            return;
        }
        PracticeContext.enter();
        try {
            chain.doFilter(req, res);
        } finally {
            PracticeContext.leave();
        }
    }
}
