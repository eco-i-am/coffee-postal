package ph.coffeepostal.pos.branchpo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.AuditService;

/** HQ side of purchase orders: review, approve (optionally fewer), reject, mark as sent. */
@RestController
@RequestMapping("/api/admin/branch-pos")
class AdminBranchPoApi {
    record Qty(@NotNull Long lineId, long qty) {}
    record ApproveReq(List<@Valid Qty> lines) {}
    record RejectReq(@NotBlank @Size(max = 300) String reason) {}

    private final BranchPoService pos;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    AdminBranchPoApi(BranchPoService pos, AuditService audit, JdbcTemplate jdbc) { this.pos = pos; this.audit = audit; this.jdbc = jdbc; }

    private long adminId(Principal who) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, who.getName());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_branch_po_admin_view')")
    List<BranchPoService.Row> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        return pos.list(null, status, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_branch_po_admin_view')")
    BranchPoService.Detail detail(@PathVariable long id) { return pos.detail(id, null); }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_branch_po_admin_edit')")
    Map<String, Object> approve(@PathVariable long id, @Valid @RequestBody ApproveReq r, Principal who) {
        Map<Long, Long> q = r.lines() == null ? Map.of() : r.lines().stream().collect(Collectors.toMap(Qty::lineId, Qty::qty, (a, b) -> b));
        pos.approve(id, adminId(who), q);
        audit.record(who.getName(), null, "BRANCH_PO_APPROVE", "branch_po", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_branch_po_admin_edit')")
    Map<String, Object> reject(@PathVariable long id, @Valid @RequestBody RejectReq r, Principal who) {
        pos.reject(id, adminId(who), r.reason());
        audit.record(who.getName(), null, "BRANCH_PO_REJECT", "branch_po", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAuthority('PERM_branch_po_admin_edit')")
    Map<String, Object> ship(@PathVariable long id, Principal who) {
        pos.ship(id);
        audit.record(who.getName(), null, "BRANCH_PO_SHIP", "branch_po", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }
}
