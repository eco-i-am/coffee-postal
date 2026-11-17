package ph.coffeepostal.pos.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.Money;

/** Cash drawer: open a shift, cash in/out, close with a count. Each cashier only ever touches their own shift. */
@RestController
@RequestMapping("/api/branch")
class ShiftApi {
    record OpenReq(@NotBlank String openingCash) {}
    record CashReq(@NotBlank String kind, @NotBlank String amount, @NotBlank @Size(max = 200) String reason) {}
    record CloseReq(@NotBlank String countedCash) {}
    record Current(ShiftService.Summary shift) {}

    private final ShiftService shifts;
    private final OrderService orders;
    private final AuditService audit;

    ShiftApi(ShiftService shifts, OrderService orders, AuditService audit) { this.shifts = shifts; this.orders = orders; this.audit = audit; }

    @GetMapping("/shift")
    @PreAuthorize("hasAuthority('PERM_cash_inout_view')")
    Current current(Principal who) { return new Current(shifts.current(orders.whoIs(who.getName()).userId())); }

    @PostMapping("/shift/open")
    @PreAuthorize("hasAuthority('PERM_cash_inout_add')")
    ShiftService.Summary open(@Valid @RequestBody OpenReq r, Principal who) {
        var me = orders.whoIs(who.getName());
        long cash = pesos(r.openingCash(), true, "openingCash");
        var s = shifts.open(me.branchId(), me.userId(), cash);
        audit.record(who.getName(), me.branchId(), "SHIFT_OPEN", "shift", String.valueOf(s.id()), null, "{\"openingCash\":" + cash + "}", null);
        return s;
    }

    @PostMapping("/shift/cash")
    @PreAuthorize("hasAuthority('PERM_cash_inout_add')")
    ShiftService.Summary cash(@Valid @RequestBody CashReq r, Principal who) {
        var me = orders.whoIs(who.getName());
        if (!r.kind().equals("IN") && !r.kind().equals("OUT")) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick cash in or cash out.");
        long amount = pesos(r.amount(), false, "amount");
        var s = shifts.addMovement(me.userId(), r.kind(), amount, r.reason());
        audit.record(who.getName(), me.branchId(), "CASH_" + r.kind(), "shift", String.valueOf(s.id()), null,
            "{\"amount\":" + amount + "}", null);
        return s;
    }

    @PostMapping("/shift/close")
    @PreAuthorize("hasAuthority('PERM_cash_inout_add')")
    ShiftService.Closed close(@Valid @RequestBody CloseReq r, Principal who) {
        var me = orders.whoIs(who.getName());
        long counted = pesos(r.countedCash(), true, "countedCash");
        var c = shifts.close(me.userId(), counted);
        audit.record(who.getName(), me.branchId(), "SHIFT_CLOSE", "shift", String.valueOf(c.id()), null,
            "{\"expected\":" + c.expected() + ",\"counted\":" + c.counted() + ",\"variance\":" + c.variance() + "}", null);
        return c;
    }

    /** Past shifts for the branch, with over/short. Managers only. */
    @GetMapping("/shifts")
    @PreAuthorize("hasAuthority('PERM_cashier_reports_view')")
    List<ShiftService.HistoryRow> history(Principal who) { return shifts.history(orders.whoIs(who.getName()).branchId()); }

    private static long pesos(String s, boolean allowZero, String field) {
        try {
            BigDecimal v = new BigDecimal(s.trim());
            if (v.signum() < 0 || (!allowZero && v.signum() == 0) || v.scale() > 2 || v.compareTo(new BigDecimal("10000000")) > 0) {
                throw new IllegalArgumentException();
            }
            return Money.ofPesos(v.toPlainString()).centavos();
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter an amount like 500 or 500.50.", Map.of(field, "Enter an amount like 500 or 500.50."));
        }
    }
}
