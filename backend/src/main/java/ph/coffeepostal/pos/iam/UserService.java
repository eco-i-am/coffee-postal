package ph.coffeepostal.pos.iam;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.SessionGuard;

/**
 * Staff accounts. HQ manages everyone; a branch owner or manager manages only their own branch's staff.
 * Safety rules: you cannot manage someone whose role has more access than yours, cannot disable or demote
 * yourself, and the last active Super Admin cannot be removed.
 */
@Service
public class UserService {
    public record UserRow(long id, String username, String fullName, String email, long roleId, String role, String scope,
                          Long branchId, String branch, String status, boolean locked, boolean hasPin, String lastLogin) {}
    public record Save(String username, String fullName, String email, Long roleId, Long branchId, String status, String password, String pin) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final RoleService roles;
    private final AuditService audit;
    private final SessionGuard.AuthVersion authVersion;

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder, RoleService roles, AuditService audit, SessionGuard.AuthVersion authVersion) {
        this.jdbc = jdbc; this.encoder = encoder; this.roles = roles; this.audit = audit; this.authVersion = authVersion;
    }

    private static final String SELECT = """
        SELECT u.id, u.username, u.full_name, u.email, u.role_id, r.name AS role, r.scope, ub.branch_id, b.name AS branch, u.status,
               (u.locked_until IS NOT NULL AND u.locked_until > now()) AS locked, (u.pin_hash IS NOT NULL) AS has_pin,
               (SELECT to_char(max(h.at) AT TIME ZONE 'Asia/Manila', 'YYYY-MM-DD HH24:MI') FROM login_history h WHERE h.user_id = u.id AND h.success) AS last_login
        FROM users u JOIN roles r ON r.id = u.role_id
        LEFT JOIN user_branches ub ON ub.user_id = u.id LEFT JOIN branches b ON b.id = ub.branch_id
        """;

    private static UserRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long bid = rs.getLong("branch_id");
        return new UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"), rs.getString("email"), rs.getLong("role_id"),
            rs.getString("role"), rs.getString("scope"), rs.wasNull() ? null : bid, rs.getString("branch"), rs.getString("status"),
            rs.getBoolean("locked"), rs.getBoolean("has_pin"), rs.getString("last_login"));
    }

    /** branchScope = null for HQ (everyone), or the branch id a branch manager is limited to. */
    public List<UserRow> list(Long branchScope) {
        return branchScope == null
            ? jdbc.query(SELECT + " ORDER BY r.scope, u.full_name", UserService::map)
            : jdbc.query(SELECT + " WHERE ub.branch_id = ? ORDER BY u.full_name", UserService::map, branchScope);
    }

    private UserRow get(long id) {
        List<UserRow> r = jdbc.query(SELECT + " WHERE u.id = ?", UserService::map, id);
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That user no longer exists.");
        return r.get(0);
    }

    /** The target must be inside the actor's reach: their branch, and a role no stronger than theirs. */
    private UserRow reachable(String actor, Long branchScope, long id) {
        UserRow u = get(id);
        if (branchScope != null && !branchScope.equals(u.branchId())) throw new ApiException(HttpStatus.NOT_FOUND, "That user no longer exists.");
        if (!roles.permSet(roles.roleOfUser(actor)).containsAll(roles.permSet(u.roleId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot manage someone with more access than you.");
        }
        return u;
    }

    private void checkRoleAllowed(String actor, Long branchScope, long roleId) {
        String scope;
        try { scope = jdbc.queryForObject("SELECT scope FROM roles WHERE id = ?", String.class, roleId); }
        catch (org.springframework.dao.EmptyResultDataAccessException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a role.", Map.of("roleId", "Pick a role.")); }
        if (branchScope != null && !"BRANCH".equals(scope)) throw new ApiException(HttpStatus.FORBIDDEN, "You can only add branch staff.");
        if (!roles.permSet(roles.roleOfUser(actor)).containsAll(roles.permSet(roleId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only give a role that has no more access than yours.");
        }
    }

    private static void requireText(String v, String field, String msg) {
        if (v == null || v.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
    }

    private String scopeOf(long roleId) { return jdbc.queryForObject("SELECT scope FROM roles WHERE id = ?", String.class, roleId); }

    private boolean branchOk(long branchId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM branches WHERE id = ? AND active", Integer.class, branchId);
        return n != null && n > 0;
    }

    @Transactional
    public long create(String actor, Long branchScope, Save s, String ip) {
        requireText(s.fullName(), "fullName", "Enter the full name.");
        requireText(s.username(), "username", "Enter a username.");
        String username = s.username().trim().toLowerCase();
        if (!username.matches("[a-z0-9._-]{3,40}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use 3 to 40 letters, numbers, dots or dashes.", Map.of("username", "Use 3 to 40 letters, numbers, dots or dashes."));
        }
        if (s.roleId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a role.", Map.of("roleId", "Pick a role."));
        checkRoleAllowed(actor, branchScope, s.roleId());
        PasswordPolicy.checkPassword(s.password(), "password");
        if (s.pin() != null && !s.pin().isBlank()) PasswordPolicy.checkPin(s.pin(), "pin");
        Long branchId = branchScope != null ? branchScope : s.branchId();
        boolean branchRole = "BRANCH".equals(scopeOf(s.roleId()));
        if (branchRole && (branchId == null || !branchOk(branchId))) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick the branch this person works at.", Map.of("branchId", "Pick a branch."));
        String email = s.email() == null || s.email().isBlank() ? null : s.email().trim().toLowerCase();
        long id;
        try {
            id = jdbc.queryForObject("""
                INSERT INTO users(username, email, full_name, password_hash, pin_hash, role_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, username, email, s.fullName().trim(), encoder.encode(s.password()),
                s.pin() == null || s.pin().isBlank() ? null : encoder.encode(s.pin()), s.roleId());
        } catch (DuplicateKeyException e) {
            boolean mail = e.getMessage() != null && e.getMessage().contains("email");
            throw new ApiException(HttpStatus.CONFLICT, mail ? "That email is already used." : "That username is taken.", Map.of(mail ? "email" : "username", "Already used."));
        }
        if (branchRole) jdbc.update("INSERT INTO user_branches(user_id, branch_id) VALUES (?, ?)", id, branchId);
        audit.record(actor, branchId, "USER_CREATE", "user", String.valueOf(id), null, "{\"username\":\"" + username + "\",\"roleId\":" + s.roleId() + "}", null);
        return id;
    }

    private int activeSuperAdmins() {
        return jdbc.queryForObject("SELECT count(*) FROM users u JOIN roles r ON r.id = u.role_id WHERE r.name = ? AND u.status = 'ACTIVE'", Integer.class, RoleService.SUPER_ADMIN);
    }

    @Transactional
    public void update(String actor, Long branchScope, long id, Save s) {
        UserRow u = reachable(actor, branchScope, id);
        boolean self = u.username().equalsIgnoreCase(actor);
        requireText(s.fullName(), "fullName", "Enter the full name.");
        long roleId = s.roleId() == null ? u.roleId() : s.roleId();
        String status = s.status() == null ? u.status() : s.status();
        if (!Set.of("ACTIVE", "DISABLED", "BLOCKED").contains(status)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown status.");
        if (roleId != u.roleId()) checkRoleAllowed(actor, branchScope, roleId);
        if (self && (roleId != u.roleId() || !"ACTIVE".equals(status))) throw new ApiException(HttpStatus.FORBIDDEN, "You cannot disable or change the role of your own account.");
        boolean wasSuper = RoleService.SUPER_ADMIN.equals(u.role());
        boolean staysSuper = roleId == u.roleId() && "ACTIVE".equals(status);
        if (wasSuper && "ACTIVE".equals(u.status()) && !staysSuper && activeSuperAdmins() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "This is the last active Super Admin. Make another one first.");
        }
        boolean branchRole = "BRANCH".equals(scopeOf(roleId));
        Long branchId = branchScope != null ? branchScope : (s.branchId() != null ? s.branchId() : u.branchId());
        if (branchRole && (branchId == null || !branchOk(branchId))) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick the branch this person works at.", Map.of("branchId", "Pick a branch."));
        String email = s.email() == null || s.email().isBlank() ? null : s.email().trim().toLowerCase();
        try {
            jdbc.update("UPDATE users SET full_name = ?, email = ?, role_id = ?, status = ?, updated_at = now() WHERE id = ?",
                s.fullName().trim(), email, roleId, status, id);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "That email is already used.", Map.of("email", "Already used."));
        }
        jdbc.update("DELETE FROM user_branches WHERE user_id = ?", id);
        if (branchRole) jdbc.update("INSERT INTO user_branches(user_id, branch_id) VALUES (?, ?)", id, branchId);
        if ("ACTIVE".equals(status) && !"ACTIVE".equals(u.status())) jdbc.update("UPDATE users SET failed_logins = 0, locked_until = NULL WHERE id = ?", id);
        authVersion.bumpUser(u.username());   // they sign in again to pick up the new role or branch
        audit.record(actor, branchId, "USER_UPDATE", "user", String.valueOf(id),
            "{\"roleId\":" + u.roleId() + ",\"status\":\"" + u.status() + "\"}", "{\"roleId\":" + roleId + ",\"status\":\"" + status + "\"}", null);
    }

    @Transactional
    public void resetPassword(String actor, Long branchScope, long id, String password) {
        UserRow u = reachable(actor, branchScope, id);
        PasswordPolicy.checkPassword(password, "password");
        jdbc.update("UPDATE users SET password_hash = ?, failed_logins = 0, locked_until = NULL, updated_at = now() WHERE id = ?", encoder.encode(password), id);
        authVersion.bumpUser(u.username());
        audit.record(actor, u.branchId(), "USER_PASSWORD_RESET", "user", String.valueOf(id), null, null, null);
    }

    @Transactional
    public void setPin(String actor, Long branchScope, long id, String pin) {
        UserRow u = reachable(actor, branchScope, id);
        PasswordPolicy.checkPin(pin, "pin");
        jdbc.update("UPDATE users SET pin_hash = ?, updated_at = now() WHERE id = ?", encoder.encode(pin), id);
        audit.record(actor, u.branchId(), "USER_PIN_SET", "user", String.valueOf(id), null, null, null);
    }

    @Transactional
    public void unlock(String actor, Long branchScope, long id) {
        UserRow u = reachable(actor, branchScope, id);
        jdbc.update("UPDATE users SET failed_logins = 0, locked_until = NULL WHERE id = ?", id);
        audit.record(actor, u.branchId(), "USER_UNLOCK", "user", String.valueOf(id), null, null, null);
    }

    /** A signed-in user changing their own password. Their other sessions end; this one carries on. */
    @Transactional
    public void changeOwnPassword(String username, String current, String next, HttpSession session) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);
        if (current == null || !encoder.matches(current, hash)) throw new ApiException(HttpStatus.BAD_REQUEST, "Your current password is not right.", Map.of("current", "Not right."));
        PasswordPolicy.checkPassword(next, "next");
        if (next.equals(current)) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a new password.", Map.of("next", "Pick a new password."));
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE username = ?", encoder.encode(next), username);
        authVersion.bumpUser(username);
        authVersion.markCurrent(session, username);
        audit.record(username, null, "PASSWORD_CHANGE", "user", username, null, null, null);
    }
}
