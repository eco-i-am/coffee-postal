package ph.coffeepostal.pos.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

class TransferApis {
    record SendReq(@NotNull Long toBranchId, @Size(max = 200) String note, @NotEmpty @Valid List<TransferService.LineReq> lines) {}

    /** Branch: send stock to another branch, and confirm what arrives. */
    @RestController
    @RequestMapping("/api/branch/transfers")
    static class BranchApi {
        private final TransferService transfers;
        private final OrderService orders;
        private final AuditService audit;

        BranchApi(TransferService transfers, OrderService orders, AuditService audit) { this.transfers = transfers; this.orders = orders; this.audit = audit; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_transfers_view')")
        List<TransferService.Transfer> mine(Principal who) { return transfers.list(orders.whoIs(who.getName()).branchId()); }

        @GetMapping("/branches")
        @PreAuthorize("hasAuthority('PERM_transfers_view')")
        List<TransferService.BranchOpt> branches(Principal who) { return transfers.otherBranches(orders.whoIs(who.getName()).branchId()); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_transfers_add')")
        Map<String, Object> send(@Valid @RequestBody SendReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            long id = transfers.send(me.branchId(), r.toBranchId(), r.note(), r.lines(), me.userId());
            audit.record(who.getName(), me.branchId(), "TRANSFER_SEND", "stock_transfer", String.valueOf(id), null, "{\"to\":" + r.toBranchId() + "}", null);
            return Map.of("id", id);
        }

        @PostMapping("/{id}/receive")
        @PreAuthorize("hasAuthority('PERM_transfers_edit')")
        Map<String, Object> receive(@PathVariable long id, Principal who) {
            var me = orders.whoIs(who.getName());
            transfers.receive(id, me.branchId(), me.userId());
            audit.record(who.getName(), me.branchId(), "TRANSFER_RECEIVE", "stock_transfer", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PostMapping("/{id}/cancel")
        @PreAuthorize("hasAuthority('PERM_transfers_edit')")
        Map<String, Object> cancel(@PathVariable long id, Principal who) {
            var me = orders.whoIs(who.getName());
            transfers.cancel(id, me.branchId(), me.userId());
            audit.record(who.getName(), me.branchId(), "TRANSFER_CANCEL", "stock_transfer", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }
    }

    /** HQ: every transfer, read only. */
    @RestController
    @RequestMapping("/api/admin/transfers")
    static class AdminApi {
        private final TransferService transfers;

        AdminApi(TransferService transfers) { this.transfers = transfers; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_admin_transfers_view')")
        List<TransferService.Transfer> all() { return transfers.list(null); }
    }
}
