package ph.coffeepostal.pos.iam;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a user + role scope + module permissions as authorities:
 *   SCOPE_ADMIN | SCOPE_BRANCH, and PERM_<module>_<view|add|edit|delete>.
 * Branch scoping (which branch_ids a BRANCH user may touch) is enforced in services, not here.
 */
@Service
class DbUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    DbUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var rows = jdbc.queryForList(
            "SELECT u.id, u.password_hash, u.status, r.id AS role_id, r.scope FROM users u JOIN roles r ON r.id=u.role_id WHERE u.username=?", username);
        if (rows.isEmpty()) throw new UsernameNotFoundException(username);
        var u = rows.get(0);
        List<GrantedAuthority> auth = new ArrayList<>();
        auth.add(new SimpleGrantedAuthority("SCOPE_" + u.get("scope")));
        jdbc.query("SELECT module,can_view,can_add,can_edit,can_delete FROM role_permissions WHERE role_id=?",
            rs -> {
                String m = rs.getString("module");
                if (rs.getBoolean("can_view"))   auth.add(new SimpleGrantedAuthority("PERM_" + m + "_view"));
                if (rs.getBoolean("can_add"))    auth.add(new SimpleGrantedAuthority("PERM_" + m + "_add"));
                if (rs.getBoolean("can_edit"))   auth.add(new SimpleGrantedAuthority("PERM_" + m + "_edit"));
                if (rs.getBoolean("can_delete")) auth.add(new SimpleGrantedAuthority("PERM_" + m + "_delete"));
            }, u.get("role_id"));
        return User.withUsername(username).password((String) u.get("password_hash"))
            .disabled(!"ACTIVE".equals(u.get("status"))).authorities(auth).build();
    }
}
