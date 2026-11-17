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
    private final JdbcTemplate jdbc;
    LoginEvents(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent e) { log(e.getAuthentication(), true); }

    @EventListener
    void onFailure(AbstractAuthenticationFailureEvent e) { log(e.getAuthentication(), false); }

    private void log(Authentication a, boolean ok) {
        String ip = a.getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : null;
        String name = a.getName();
        jdbc.update("INSERT INTO login_history(user_id, username, success, ip) VALUES ((SELECT id FROM users WHERE username = ?), ?, ?, ?)",
            name, name, ok, ip);
    }
}
