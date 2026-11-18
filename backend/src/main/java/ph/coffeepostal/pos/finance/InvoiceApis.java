package ph.coffeepostal.pos.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.Money;
import ph.coffeepostal.pos.shared.PesoInput;

/** HQ side: receivables. Record payments received from branches. */
@RestController
@RequestMapping("/api/admin/receivables")
class AdminInvoiceApi {
    record PayReq(@NotBlank String amount, @NotBlank String method, @Size(max = 80) String reference, String paidOn) {}

    private final InvoiceService invoices;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    AdminInvoiceApi(InvoiceService invoices, AuditService audit, JdbcTemplate jdbc) { this.invoices = invoices; this.audit = audit; this.jdbc = jdbc; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_receivables_view')")
    List<InvoiceService.Row> list(@RequestParam(required = false) String filter, @RequestParam(defaultValue = "100") int limit) {
        return invoices.list(null, filter, limit);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PERM_receivables_view')")
    InvoiceService.Summary summary() { return invoices.summary(null); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_receivables_view')")
    InvoiceService.Detail detail(@PathVariable long id) { return invoices.detail(id, null); }

    @PostMapping("/{id}/payments")
    @PreAuthorize("hasAuthority('PERM_receivables_add')")
    Map<String, Object> pay(@PathVariable long id, @Valid @RequestBody PayReq r, Principal who) {
        long amount = PesoInput.parse(r.amount(), false, "amount", "Enter an amount like 1500 or 1500.50.");
        LocalDate paidOn;
        try { paidOn = r.paidOn() == null || r.paidOn().isBlank() ? LocalDate.now(InvoiceService.MANILA) : LocalDate.parse(r.paidOn()); }
        catch (DateTimeParseException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a valid date.", Map.of("paidOn", "Pick a valid date.")); }
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, who.getName());
        invoices.recordPayment(id, userId, amount, r.method(), r.reference(), paidOn);
        audit.record(who.getName(), null, "INVOICE_PAYMENT", "invoice", String.valueOf(id), null,
            "{\"amount\":" + amount + ",\"method\":\"" + r.method() + "\"}", null);
        return Map.of("id", id);
    }

}

/** Branch side: payables. Read only; the branch sees what it owes and what has been paid. */
@RestController
@RequestMapping("/api/branch/payables")
class BranchInvoiceApi {
    private final InvoiceService invoices;
    private final OrderService orders;

    BranchInvoiceApi(InvoiceService invoices, OrderService orders) { this.invoices = invoices; this.orders = orders; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_payables_view')")
    List<InvoiceService.Row> list(@RequestParam(required = false) String filter, @RequestParam(defaultValue = "100") int limit, Principal who) {
        return invoices.list(orders.whoIs(who.getName()).branchId(), filter, limit);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PERM_payables_view')")
    InvoiceService.Summary summary(Principal who) { return invoices.summary(orders.whoIs(who.getName()).branchId()); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_payables_view')")
    InvoiceService.Detail detail(@PathVariable long id, Principal who) {
        return invoices.detail(id, orders.whoIs(who.getName()).branchId());
    }
}
