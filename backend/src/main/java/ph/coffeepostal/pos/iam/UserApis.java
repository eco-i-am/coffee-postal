package ph.coffeepostal.pos.iam;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.AuditService;

record SecretBody(String password, String pin) {}
record PasswordChange(String current, String next) {}
record RoleBody(String name, String scope, String description, List<RoleService.Perm> permissions) {}

/** HQ: every account. */
@RestController
@RequestMapping("/api/admin/users")
class AdminUserApi {
    private final UserService users;
    AdminUserApi(UserService users) { this.users = users; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_users_admin_view')")
    List<UserService.UserRow> list() { return users.list(null); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_users_admin_add')")
    Map<String, Long> create(@RequestBody UserService.Save s, Principal who, HttpServletRequest req) {
        return Map.of("id", users.create(who.getName(), null, s, req.getRemoteAddr()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_users_admin_edit')")
    void update(@PathVariable long id, @RequestBody UserService.Save s, Principal who) { users.update(who.getName(), null, id, s); }

    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('PERM_users_admin_edit')")
    void password(@PathVariable long id, @RequestBody SecretBody b, Principal who) { users.resetPassword(who.getName(), null, id, b.password()); }

    @PostMapping("/{id}/pin")
    @PreAuthorize("hasAuthority('PERM_users_admin_edit')")
    void pin(@PathVariable long id, @RequestBody SecretBody b, Principal who) { users.setPin(who.getName(), null, id, b.pin()); }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('PERM_users_admin_edit')")
    void unlock(@PathVariable long id, Principal who) { users.unlock(who.getName(), null, id); }
}

/** A branch owner or manager: only their own branch's staff. */
@RestController
@RequestMapping("/api/branch/users")
class BranchUserApi {
    private final UserService users;
    private final OrderService orders;
    private final RoleService roles;
    BranchUserApi(UserService users, OrderService orders, RoleService roles) { this.users = users; this.orders = orders; this.roles = roles; }

    private long branch(Principal who) { return orders.whoIs(who.getName()).branchId(); }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_branch_users_view')")
    List<UserService.UserRow> list(Principal who) { return users.list(branch(who)); }

    /** Roles this person is allowed to hand out: branch roles no stronger than their own. */
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('PERM_branch_users_view')")
    List<RoleService.RoleRow> assignable(Principal who) {
        var mine = roles.permSet(roles.roleOfUser(who.getName()));
        return roles.list().stream().filter(r -> "BRANCH".equals(r.scope()) && mine.containsAll(roles.permSet(r.id()))).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_branch_users_add')")
    Map<String, Long> create(@RequestBody UserService.Save s, Principal who, HttpServletRequest req) {
        return Map.of("id", users.create(who.getName(), branch(who), s, req.getRemoteAddr()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_branch_users_edit')")
    void update(@PathVariable long id, @RequestBody UserService.Save s, Principal who) { users.update(who.getName(), branch(who), id, s); }

    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('PERM_branch_users_edit')")
    void password(@PathVariable long id, @RequestBody SecretBody b, Principal who) { users.resetPassword(who.getName(), branch(who), id, b.password()); }

    @PostMapping("/{id}/pin")
    @PreAuthorize("hasAuthority('PERM_branch_users_edit')")
    void pin(@PathVariable long id, @RequestBody SecretBody b, Principal who) { users.setPin(who.getName(), branch(who), id, b.pin()); }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('PERM_branch_users_edit')")
    void unlock(@PathVariable long id, Principal who) { users.unlock(who.getName(), branch(who), id); }
}

/** HQ: roles and their permission grid. */
@RestController
@RequestMapping("/api/admin/roles")
class AdminRoleApi {
    private final RoleService roles;
    private final AuditService audit;
    AdminRoleApi(RoleService roles, AuditService audit) { this.roles = roles; this.audit = audit; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_roles_view')")
    List<RoleService.RoleRow> list() { return roles.list(); }

    @GetMapping("/modules")
    @PreAuthorize("hasAuthority('PERM_roles_view')")
    List<RoleService.ModuleRow> modules() { return roles.modules(); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_roles_view')")
    RoleService.RoleDetail detail(@PathVariable long id) { return roles.detail(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_roles_add')")
    Map<String, Long> create(@RequestBody RoleBody b, Principal who) {
        long id = roles.create(roles.roleOfUser(who.getName()), b.name(), b.scope(), b.description(), b.permissions() == null ? List.of() : b.permissions());
        audit.record(who.getName(), null, "ROLE_CREATE", "role", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_roles_edit')")
    void update(@PathVariable long id, @RequestBody RoleBody b, Principal who) {
        roles.update(roles.roleOfUser(who.getName()), id, b.name(), b.description(), b.permissions() == null ? List.of() : b.permissions());
        audit.record(who.getName(), null, "ROLE_UPDATE", "role", String.valueOf(id), null, null, null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_roles_delete')")
    void delete(@PathVariable long id, Principal who) {
        roles.delete(id);
        audit.record(who.getName(), null, "ROLE_DELETE", "role", String.valueOf(id), null, null, null);
    }
}

/** Anyone signed in can change their own password. */
@RestController
class OwnPasswordApi {
    private final UserService users;
    OwnPasswordApi(UserService users) { this.users = users; }

    @PostMapping("/api/me/password")
    void change(@RequestBody PasswordChange b, Principal who, HttpServletRequest req) {
        users.changeOwnPassword(who.getName(), b.current(), b.next(), req.getSession(false));
    }
}
