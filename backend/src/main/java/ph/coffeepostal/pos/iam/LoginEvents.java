package ph.coffeepostal.pos.iam;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/** Login history (success and failure) for the "View Login History" requirement. */
@Component
class LoginEvents {
    static final int MAX_FAILURES = 5;
    static final int LOCK_MINUTES = 15;

    private final JdbcTemplate jdbc;
    LoginEvents(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent e) { log(e.getAuthentication(), true); }

    @EventListener
    void onFailure(AbstractAuthenticationFailureEvent e) { log(e.getAuthentication(), false); }

    private void log(Authentication a, boolean ok) {
        String ip = a.getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : null;
        String name = a.getName();
        String ua = null;
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
            ua = sra.getRequest().getHeader("User-Agent");
            if (ua != null && ua.length() > 300) ua = ua.substring(0, 300);
        }
        jdbc.update("INSERT INTO login_history(user_id, username, success, ip, user_agent) VALUES ((SELECT id FROM users WHERE username = ?), ?, ?, ?, ?)",
            name, name, ok, ip, ua);
        if (ok) {
            jdbc.update("UPDATE users SET failed_logins = 0, locked_until = NULL WHERE username = ?", name);
        } else {
            // count the miss, and lock for 15 minutes on the 5th in a row. Attempts while already locked do not extend the lock.
            jdbc.update("""
                UPDATE users SET failed_logins = failed_logins + 1,
                    locked_until = CASE WHEN failed_logins + 1 >= ? THEN now() + make_interval(mins => ?) ELSE locked_until END
                WHERE username = ? AND (locked_until IS NULL OR locked_until < now())
                """, MAX_FAILURES, LOCK_MINUTES, name);
        }
    }
}
