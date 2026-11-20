package ph.coffeepostal.pos.branch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** HQ: Branch List (create, open/close). */
@RestController
@RequestMapping("/api/admin/branches")
class BranchAdminApi {
    record BranchDto(long id, String code, String name, String address, String phone, String tin, boolean vatRegistered, boolean active, String type) {
        static BranchDto of(Branch b) { return new BranchDto(b.getId(), b.getCode(), b.getName(), b.getAddress(), b.getPhone(), b.getTin(), b.isVatRegistered(), b.isActive(), b.getBranchType()); }
    }
    record BranchReq(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 120) String name,
                     @Size(max = 300) String address, @Size(max = 40) String phone, String type) {}
    record BranchEditReq(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 120) String name,
                         @Size(max = 300) String address, @Size(max = 40) String phone, @Size(max = 30) String tin, boolean vatRegistered, String type) {}

    private final BranchRepository branches;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    BranchAdminApi(BranchRepository branches, AuditService audit, JdbcTemplate jdbc) { this.branches = branches; this.audit = audit; this.jdbc = jdbc; }

    /** Express or Kiosk. Required for a new branch; an older one (flagship, sample) may have none. */
    private static String type(String t, boolean required) {
        String x = t == null ? "" : t.trim().toUpperCase();
        if (x.isEmpty() && !required) return null;
        if (!x.equals("EXPRESS") && !x.equals("KIOSK")) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick Express or Kiosk.", Map.of("type", "Pick Express or Kiosk."));
        return x;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_branches_view')")
    List<BranchDto> list() { return branches.findAllByOrderByNameAsc().stream().map(BranchDto::of).toList(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_branches_add')")
    BranchDto create(@Valid @RequestBody BranchReq r, Principal who) {
        String code = r.code().trim().toUpperCase();
        String type = type(r.type(), true);
        if (branches.existsByCodeIgnoreCase(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "That branch code is already used.", Map.of("code", "Already used."));
        }
        Branch b = new Branch(code, r.name().trim(), r.address(), r.phone());
        b.setBranchType(type);
        b = branches.save(b);
        audit.record(who.getName(), b.getId(), "BRANCH_CREATE", "branch", String.valueOf(b.getId()), null,
            "{\"code\":\"" + b.getCode() + "\",\"name\":\"" + b.getName().replace("\"", "'") + "\"}", null);
        return BranchDto.of(b);
    }

    /** Change a branch's details. A new code must not belong to another branch. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_branches_edit')")
    BranchDto update(@PathVariable long id, @Valid @RequestBody BranchEditReq r, Principal who) {
        Branch b = branches.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That branch no longer exists."));
        String code = r.code().trim().toUpperCase();
        String type = type(r.type(), false);
        if (branches.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "That branch code is already used.", Map.of("code", "Already used."));
        }
        String before = "{\"code\":\"" + b.getCode() + "\",\"name\":\"" + clean(b.getName()) + "\",\"vat\":" + b.isVatRegistered() + "}";
        b.setCode(code);
        b.setName(r.name().trim());
        b.setAddress(blank(r.address()));
        b.setPhone(blank(r.phone()));
        b.setTin(blank(r.tin()));
        b.setVatRegistered(r.vatRegistered());
        b.setBranchType(type);
        branches.save(b);
        audit.record(who.getName(), b.getId(), "BRANCH_UPDATE", "branch", String.valueOf(id), before,
            "{\"code\":\"" + code + "\",\"name\":\"" + clean(b.getName()) + "\",\"vat\":" + b.isVatRegistered() + "}", null);
        return BranchDto.of(b);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String clean(String s) { return s == null ? "" : s.replace("\"", "'"); }

    /**
     * Delete a branch that was never used. Anything tied to it (sales, shifts, staff, stock...) stops the delete and the
     * branch is set inactive instead. Its old audit lines stay, no longer pointing at a branch.
     */
    @DeleteMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('PERM_branches_delete')")
    void delete(@PathVariable long id, @RequestParam(defaultValue = "false") boolean withUsers, Principal who) {
        Branch b = branches.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That branch no longer exists."));
        String code = b.getCode(), name = clean(b.getName());
        int users = 0;
        try {
            if (withUsers) {
                // accounts that belong to this branch only; someone who also works at another branch just loses this one.
                // Never the signed-in user, who would be locking themselves out.
                List<Long> own = jdbc.queryForList("""
                    SELECT u.id FROM users u JOIN user_branches ub ON ub.user_id = u.id AND ub.branch_id = ?
                    WHERE lower(u.username) <> lower(?)
                      AND NOT EXISTS (SELECT 1 FROM user_branches o WHERE o.user_id = u.id AND o.branch_id <> ?)
                    """, Long.class, id, who.getName(), id);
                jdbc.update("DELETE FROM user_branches WHERE branch_id = ?", id);
                for (long uid : own) jdbc.update("DELETE FROM users WHERE id = ?", uid);
                users = own.size();
            }
            jdbc.update("UPDATE audit_logs SET branch_id = NULL WHERE branch_id = ?", id);
            jdbc.update("DELETE FROM branches WHERE id = ?", id);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "This branch, or one of its accounts, has sales, shifts, stock or other records, so it cannot be deleted. Set it to inactive instead: it stops selling and the history stays.");
        }
        audit.record(who.getName(), null, "BRANCH_DELETE", "branch", String.valueOf(id), "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"usersDeleted\":" + users + "}", null, null);
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('PERM_branches_edit')")
    BranchDto toggle(@PathVariable long id, Principal who) {
        Branch b = branches.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That branch no longer exists."));
        boolean before = b.isActive();
        b.setActive(!before);
        branches.save(b);
        audit.record(who.getName(), b.getId(), "BRANCH_TOGGLE", "branch", String.valueOf(id),
            "{\"active\":" + before + "}", "{\"active\":" + !before + "}", null);
        return BranchDto.of(b);
    }
}
