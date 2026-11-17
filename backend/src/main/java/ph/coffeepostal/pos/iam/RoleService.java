package ph.coffeepostal.pos.iam;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.SessionGuard;

/**
 * Roles and their permission matrix (module x view/add/edit/delete).
 * The rule that keeps this safe: you can only hand out permissions you hold yourself, so nobody can
 * promote themselves or a friend above their own level. Super Admin is fixed and cannot be changed.
 */
@Service
public class RoleService {
    public record RoleRow(long id, String name, String scope, String description, int users, int modules, boolean locked) {}
    public record Perm(String module, boolean view, boolean add, boolean edit, boolean delete) {}
    public record RoleDetail(RoleRow role, List<Perm> permissions) {}
    public record ModuleRow(String code, String scope, String label) {}

    static final String SUPER_ADMIN = "Super Admin";

    private final JdbcTemplate jdbc;
    private final SessionGuard.AuthVersion authVersion;

    public RoleService(JdbcTemplate jdbc, SessionGuard.AuthVersion authVersion) { this.jdbc = jdbc; this.authVersion = authVersion; }

    /** "module:view" style keys for everything a role grants. */
    public Set<String> permSet(long roleId) {
        Set<String> out = new HashSet<>();
        jdbc.query("SELECT module, can_view, can_add, can_edit, can_delete FROM role_permissions WHERE role_id = ?", rs -> {
            String m = rs.getString(1);
            if (rs.getBoolean(2)) out.add(m + ":view");
            if (rs.getBoolean(3)) out.add(m + ":add");
            if (rs.getBoolean(4)) out.add(m + ":edit");
            if (rs.getBoolean(5)) out.add(m + ":delete");
        }, roleId);
        return out;
    }

    public long roleOfUser(String username) {
        return jdbc.queryForObject("SELECT role_id FROM users WHERE username = ?", Long.class, username);
    }

    public List<RoleRow> list() {
        return jdbc.query("""
            SELECT r.id, r.name, r.scope, r.description,
                   (SELECT count(*) FROM users u WHERE u.role_id = r.id) AS users,
                   (SELECT count(*) FROM role_permissions p WHERE p.role_id = r.id AND (p.can_view OR p.can_add OR p.can_edit OR p.can_delete)) AS modules
            FROM roles r ORDER BY r.scope, r.name
            """, (rs, i) -> new RoleRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                SUPER_ADMIN.equals(rs.getString(2))));
    }

    public List<ModuleRow> modules() {
        return jdbc.query("SELECT code, scope, label FROM modules ORDER BY scope, label",
            (rs, i) -> new ModuleRow(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public RoleDetail detail(long id) {
        RoleRow r = list().stream().filter(x -> x.id() == id).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That role no longer exists."));
        List<Perm> perms = jdbc.query("""
            SELECT module, can_view, can_add, can_edit, can_delete FROM role_permissions WHERE role_id = ?
            AND (can_view OR can_add OR can_edit OR can_delete) ORDER BY module
            """, (rs, i) -> new Perm(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5)), id);
        return new RoleDetail(r, perms);
    }

    /** Cleans the grid: add/edit/delete imply view, unknown modules and wrong-scope modules are refused, empty rows dropped. */
    private Map<String, Perm> clean(String scope, List<Perm> in, Set<String> actorPerms) {
        Map<String, String> moduleScope = new LinkedHashMap<>();
        modules().forEach(m -> moduleScope.put(m.code(), m.scope()));
        Map<String, Perm> out = new LinkedHashMap<>();
        for (Perm p : in) {
            if (!moduleScope.containsKey(p.module())) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown module: " + p.module());
            if (scope.equals("BRANCH") && !moduleScope.get(p.module()).equals("BRANCH")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "A branch role cannot include HQ screens.");
            }
            boolean any = p.view() || p.add() || p.edit() || p.delete();
            if (!any) continue;
            Perm fixed = new Perm(p.module(), true, p.add(), p.edit(), p.delete());
            for (String a : List.of("view", p.add() ? "add" : "", p.edit() ? "edit" : "", p.delete() ? "delete" : "")) {
                if (!a.isEmpty() && !actorPerms.contains(p.module() + ":" + a)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "You can only give permissions you have yourself.");
                }
            }
            out.put(p.module(), fixed);
        }
        return out;
    }

    private void checkName(String name, Long exceptId) {
        if (name == null || name.trim().length() < 2 || name.trim().length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Give the role a name (2 to 60 characters).", Map.of("name", "Give the role a name."));
        }
        Integer n = jdbc.queryForObject("SELECT count(*) FROM roles WHERE lower(name) = lower(?) AND id <> ?", Integer.class, name.trim(), exceptId == null ? -1L : exceptId);
        if (n != null && n > 0) throw new ApiException(HttpStatus.CONFLICT, "A role with that name already exists.", Map.of("name", "Already used."));
    }

    private void write(long roleId, Map<String, Perm> perms) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
        for (Perm p : perms.values()) {
            jdbc.update("INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete) VALUES (?, ?, ?, ?, ?, ?)",
                roleId, p.module(), p.view(), p.add(), p.edit(), p.delete());
        }
    }

    @Transactional
    public long create(long actorRoleId, String name, String scope, String description, List<Perm> perms) {
        if (!"ADMIN".equals(scope) && !"BRANCH".equals(scope)) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick HQ or Branch.", Map.of("scope", "Pick one."));
        checkName(name, null);
        Map<String, Perm> cleaned = clean(scope, perms, permSet(actorRoleId));
        long id = jdbc.queryForObject("INSERT INTO roles(name, scope, description) VALUES (?, ?, ?) RETURNING id", Long.class,
            name.trim(), scope, blank(description));
        write(id, cleaned);
        return id;
    }

    @Transactional
    public void update(long actorRoleId, long id, String name, String description, List<Perm> perms) {
        RoleDetail current = detail(id);
        if (current.role().locked()) throw new ApiException(HttpStatus.FORBIDDEN, "The Super Admin role cannot be changed.");
        Set<String> actor = permSet(actorRoleId);
        if (!actor.containsAll(permSet(id))) throw new ApiException(HttpStatus.FORBIDDEN, "You cannot edit a role that has more access than you do.");
        checkName(name, id);
        Map<String, Perm> cleaned = clean(current.role().scope(), perms, actor);
        jdbc.update("UPDATE roles SET name = ?, description = ? WHERE id = ?", name.trim(), blank(description), id);
        write(id, cleaned);
        authVersion.bump();   // everyone signs in again so the new permissions apply
    }

    @Transactional
    public void delete(long id) {
        RoleRow r = detail(id).role();
        if (r.locked()) throw new ApiException(HttpStatus.FORBIDDEN, "The Super Admin role cannot be deleted.");
        if (r.users() > 0) throw new ApiException(HttpStatus.CONFLICT, "This role still has " + r.users() + " user(s). Move them to another role first.");
        jdbc.update("DELETE FROM roles WHERE id = ?", id);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
