package ph.coffeepostal.pos.branchpo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** Branch side of purchase orders: create, cancel, receive. Scoped to the signed-in user's branch. */
@RestController
@RequestMapping("/api/branch/purchase-orders")
class BranchPoApi {
    record LineReq(@NotNull Long itemId, long qty) {}
    record CreateReq(@Size(max = 300) String note, @NotEmpty @Size(max = 100) List<@Valid LineReq> lines, java.time.LocalDate deliverOn) {}
    record Qty(@NotNull Long lineId, long qty) {}
    record ReceiveReq(@NotNull List<@Valid Qty> lines) {}

    private final BranchPoService pos;
    private final OrderService orders;
    private final AuditService audit;

    BranchPoApi(BranchPoService pos, OrderService orders, AuditService audit) { this.pos = pos; this.orders = orders; this.audit = audit; }

    /** The lead time and the earliest delivery day, for the order form. */
    @GetMapping("/rules")
    @PreAuthorize("hasAnyAuthority('PERM_branch_po_view', 'PERM_branch_po_receive')")
    BranchPoService.Rules rules() { return pos.rules(); }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_branch_po_view', 'PERM_branch_po_receive')")
    List<BranchPoService.Row> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit, Principal who) {
        return pos.list(orders.whoIs(who.getName()).branchId(), status, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_branch_po_view', 'PERM_branch_po_receive')")
    BranchPoService.Detail detail(@PathVariable long id, Principal who) {
        return pos.detail(id, orders.whoIs(who.getName()).branchId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_branch_po_add')")
    Map<String, Object> create(@Valid @RequestBody CreateReq r, Principal who) {
        var me = orders.whoIs(who.getName());
        long id = pos.create(me.branchId(), me.userId(), r.note(),
            r.lines().stream().map(l -> new BranchPoService.NewLine(l.itemId(), l.qty())).toList(), r.deliverOn());
        audit.record(who.getName(), me.branchId(), "BRANCH_PO_CREATE", "branch_po", String.valueOf(id), null,
            "{\"lines\":" + r.lines().size() + "}", null);
        return Map.of("id", id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_branch_po_edit')")
    Map<String, Object> cancel(@PathVariable long id, Principal who) {
        var me = orders.whoIs(who.getName());
        pos.cancel(id, me.branchId(), me.userId());
        audit.record(who.getName(), me.branchId(), "BRANCH_PO_CANCEL", "branch_po", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('PERM_branch_po_receive')")
    Map<String, Object> receive(@PathVariable long id, @Valid @RequestBody ReceiveReq r, Principal who) {
        var me = orders.whoIs(who.getName());
        Map<Long, Long> q = r.lines().stream().collect(Collectors.toMap(Qty::lineId, Qty::qty, (a, b) -> b));
        pos.receive(id, me.branchId(), me.userId(), q);
        audit.record(who.getName(), me.branchId(), "BRANCH_PO_RECEIVE", "branch_po", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('PERM_branch_po_edit')")
    Map<String, Object> closeShort(@PathVariable long id, Principal who) {
        var me = orders.whoIs(who.getName());
        pos.closeShort(id, me.branchId(), me.userId());
        audit.record(who.getName(), me.branchId(), "BRANCH_PO_CLOSE", "branch_po", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }
}
