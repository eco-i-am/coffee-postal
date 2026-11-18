package ph.coffeepostal.pos.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.PesoInput;

class SupplierApis {
    record SupplierReq(@NotBlank @Size(max = 120) String name, @Size(max = 120) String contact, @Size(max = 40) String phone,
                       @Size(max = 120) String email, @Size(max = 200) String address, @Min(0) @Max(365) int termsDays, Boolean active) {}
    record PoReq(@NotNull Long supplierId, @NotNull Long branchId, @Size(max = 200) String note, LocalDate expectedOn,
                 @NotEmpty List<SupplierService.NewLine> lines) {}
    record ReceiveReq(@NotEmpty List<SupplierService.Receive> lines) {}
    record PayReq(@NotBlank String amount, @NotBlank String method, @Size(max = 60) String reference, LocalDate paidOn) {}

    @RestController
    @RequestMapping("/api/admin/suppliers")
    static class SuppliersApi {
        private final SupplierService svc;
        private final AuditService audit;

        SuppliersApi(SupplierService svc, AuditService audit) { this.svc = svc; this.audit = audit; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_suppliers_view')")
        List<SupplierService.Supplier> list() { return svc.suppliers(); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_suppliers_add')")
        Map<String, Object> add(@Valid @RequestBody SupplierReq r, Principal who) {
            long id = svc.addSupplier(r.name().trim(), r.contact(), r.phone(), r.email(), r.address(), r.termsDays());
            audit.record(who.getName(), null, "SUPPLIER_CREATE", "supplier", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PostMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_suppliers_edit')")
        Map<String, Object> update(@PathVariable long id, @Valid @RequestBody SupplierReq r, Principal who) {
            svc.updateSupplier(id, r.name().trim(), r.contact(), r.phone(), r.email(), r.address(), r.termsDays(), r.active());
            audit.record(who.getName(), null, "SUPPLIER_UPDATE", "supplier", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }
    }

    @RestController
    @RequestMapping("/api/admin/supplier-pos")
    static class PoApi {
        private final SupplierService svc;
        private final AuditService audit;

        PoApi(SupplierService svc, AuditService audit) { this.svc = svc; this.audit = audit; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_supplier_po_view')")
        List<SupplierService.Po> list(@RequestParam(required = false) Long supplierId) { return svc.list(supplierId); }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_supplier_po_view')")
        SupplierService.Po one(@PathVariable long id) {
            return svc.get(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That order no longer exists."));
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_supplier_po_add')")
        Map<String, Object> create(@Valid @RequestBody PoReq r, Principal who) {
            long id = svc.createPo(r.supplierId(), r.branchId(), r.note(), r.expectedOn(), r.lines(), svc.userId(who.getName()));
            audit.record(who.getName(), null, "SUPPLIER_PO_CREATE", "supplier_po", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PostMapping("/{id}/receive")
        @PreAuthorize("hasAuthority('PERM_supplier_po_edit')")
        Map<String, Object> receive(@PathVariable long id, @Valid @RequestBody ReceiveReq r, Principal who) {
            svc.receive(id, r.lines(), svc.userId(who.getName()));
            audit.record(who.getName(), null, "SUPPLIER_PO_RECEIVE", "supplier_po", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PostMapping("/{id}/close")
        @PreAuthorize("hasAuthority('PERM_supplier_po_edit')")
        Map<String, Object> close(@PathVariable long id, Principal who) {
            svc.closeShort(id);
            audit.record(who.getName(), null, "SUPPLIER_PO_CLOSE", "supplier_po", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PostMapping("/{id}/cancel")
        @PreAuthorize("hasAuthority('PERM_supplier_po_edit')")
        Map<String, Object> cancel(@PathVariable long id, Principal who) {
            svc.cancel(id);
            audit.record(who.getName(), null, "SUPPLIER_PO_CANCEL", "supplier_po", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PostMapping("/{id}/payments")
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_supplier_po_edit')")
        Map<String, Object> pay(@PathVariable long id, @Valid @RequestBody PayReq r, Principal who) {
            long amount = PesoInput.parse(r.amount(), false, "amount", "Enter an amount like 1500 or 1500.50.");
            svc.pay(id, amount, r.method(), r.reference(), r.paidOn() == null ? LocalDate.now(java.time.ZoneId.of("Asia/Manila")) : r.paidOn(), svc.userId(who.getName()));
            audit.record(who.getName(), null, "SUPPLIER_PAYMENT", "supplier_po", String.valueOf(id), null, "{\"amount\":" + amount + "}", null);
            return Map.of("id", id);
        }
    }
}
