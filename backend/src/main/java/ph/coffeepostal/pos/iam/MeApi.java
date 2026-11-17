package ph.coffeepostal.pos.iam;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Who is signed in, what they may do, and (for branch staff) which branch. The UI builds its menu from this. */
@RestController
@RequestMapping("/api")
class MeApi {
    record Me(String username, String fullName, String role, String scope, List<String> permissions,
              Long branchId, String branchName) {}

    private final JdbcTemplate jdbc;
    MeApi(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Doubles as the "are my cookies still good?" check: 401 means sign in again. */
    @GetMapping("/me")
    Me me(Authentication auth, Principal who) {
        var rows = jdbc.queryForList("""
            SELECT u.full_name, r.name AS role FROM users u JOIN roles r ON r.id = u.role_id WHERE u.username = ?
            """, who.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var branch = jdbc.queryForList("""
            SELECT b.id, b.name FROM user_branches ub JOIN branches b ON b.id = ub.branch_id
            JOIN users u ON u.id = ub.user_id WHERE u.username = ? ORDER BY b.id LIMIT 1
            """, who.getName());
        String scope = auth.getAuthorities().stream().map(a -> a.getAuthority())
            .filter(a -> a.startsWith("SCOPE_")).map(a -> a.substring(6)).findFirst().orElse("");
        List<String> perms = auth.getAuthorities().stream().map(a -> a.getAuthority())
            .filter(a -> a.startsWith("PERM_")).map(a -> a.substring(5)).sorted().toList();
        return new Me(who.getName(), (String) rows.get(0).get("full_name"), (String) rows.get(0).get("role"), scope, perms,
            branch.isEmpty() ? null : ((Number) branch.get(0).get("id")).longValue(),
            branch.isEmpty() ? null : (String) branch.get(0).get("name"));
    }
}
