package ph.coffeepostal.pos.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A session keeps the permissions the user had when they signed in. This filter makes changes apply at once:
 *  - a user who is disabled, blocked or deleted is signed out on their next request;
 *  - bump() (a role's permissions changed) signs everyone out, so permissions reload at the next sign-in;
 *  - bumpUser(name) (that user's role, branches or password changed) signs out just that user.
 * One app instance holds all sessions in memory, so counters in memory are enough.
 *
 * Deliberately NOT a @Component: Spring Boot would register such a filter globally, before Spring Security has
 * loaded the user, and OncePerRequestFilter would then skip the run that matters. SecurityConfig adds it to the chain.
 */
public class SessionGuard extends OncePerRequestFilter {

    @Component
    public static class AuthVersion {
        private final AtomicLong global = new AtomicLong();
        private final Map<String, AtomicLong> perUser = new ConcurrentHashMap<>();

        /** Sign everyone out (role permissions changed). */
        public void bump() { global.incrementAndGet(); }
        /** Sign this user out everywhere (their role, branches or password changed). */
        public void bumpUser(String username) { perUser.computeIfAbsent(username.toLowerCase(), k -> new AtomicLong()).incrementAndGet(); }

        long global() { return global.get(); }
        long user(String username) { AtomicLong v = perUser.get(username.toLowerCase()); return v == null ? 0 : v.get(); }

        /** Lets a session that just changed its own password carry on while all the user's other sessions end. */
        public void markCurrent(HttpSession session, String username) {
            if (session == null) return;
            session.setAttribute(G, global());
            session.setAttribute(U, user(username));
        }
    }

    private static final String G = "av.global";
    private static final String U = "av.user";

    private final JdbcTemplate jdbc;
    private final AuthVersion version;

    public SessionGuard(JdbcTemplate jdbc, AuthVersion version) { this.jdbc = jdbc; this.version = version; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken)) {
            HttpSession session = req.getSession(false);
            boolean stale = false;
            if (session != null) {
                Object g = session.getAttribute(G), u = session.getAttribute(U);
                if (g == null || u == null) version.markCurrent(session, a.getName());   // first request after sign-in
                else stale = (Long) g < version.global() || (Long) u < version.user(a.getName());
            }
            if (!stale) {
                List<String> status = jdbc.queryForList("SELECT status FROM users WHERE username = ?", String.class, a.getName());
                stale = status.isEmpty() || !"ACTIVE".equals(status.get(0));
            }
            if (stale) {
                SecurityContextHolder.clearContext();
                if (session != null) session.invalidate();
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
