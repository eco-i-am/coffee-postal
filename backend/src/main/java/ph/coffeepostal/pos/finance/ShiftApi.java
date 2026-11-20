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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.Money;
import ph.coffeepostal.pos.shared.PesoInput;

/** Cash drawer: open a shift, cash in/out, close with a count. Each cashier only ever touches their own shift. */
@RestController
@RequestMapping("/api/branch")
class ShiftApi {
    record OpenReq(@NotBlank String openingCash, String photo) {}
    record CashReq(@NotBlank String kind, @NotBlank String amount, @NotBlank @Size(max = 200) String reason) {}
    record CloseReq(@NotBlank String countedCash) {}
    record Current(ShiftService.Summary shift) {}

    private final ShiftService shifts;
    private final ReadingService readings;
    private final OrderService orders;
    private final AuditService audit;

    ShiftApi(ShiftService shifts, ReadingService readings, OrderService orders, AuditService audit) {
        this.shifts = shifts; this.readings = readings; this.orders = orders; this.audit = audit;
    }

    @GetMapping("/shift")
    @PreAuthorize("hasAuthority('PERM_cash_inout_view')")
    Current current(Principal who) { return new Current(shifts.current(orders.whoIs(who.getName()).userId())); }

    @PostMapping("/shift/open")
    @PreAuthorize("hasAuthority('PERM_cash_inout_add')")
    ShiftService.Summary open(@Valid @RequestBody OpenReq r, Principal who) {
        var me = orders.whoIs(who.getName());
        long cash = pesos(r.openingCash(), true, "openingCash");
        byte[] photo = ph.coffeepostal.pos.hr.PhotoService.decode(r.photo());
        if (photo == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Take a photo to open your shift. Allow the camera for this site if it asks.", Map.of("photo", "Photo needed."));
        var s = shifts.open(me.branchId(), me.userId(), cash);
        shifts.savePhoto(s.id(), photo);
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

    /** X reading: the report on your open shift. Changes nothing. */
    @GetMapping("/shift/reading")
    @PreAuthorize("hasAuthority('PERM_cash_inout_view')")
    ReadingService.Reading xReading(Principal who) {
        var me = orders.whoIs(who.getName());
        var cur = shifts.current(me.userId());
        if (cur == null) throw new ApiException(HttpStatus.CONFLICT, "You do not have an open shift.");
        audit.record(who.getName(), me.branchId(), "X_READING", "shift", String.valueOf(cur.id()), null, null, null);
        return readings.reading(cur.id());
    }

    /** The reading of any shift in this branch (a Z reading once closed). A cashier sees only their own; managers see all. */
    @GetMapping("/shifts/{id}/reading")
    @PreAuthorize("hasAnyAuthority('PERM_cash_inout_view','PERM_cashier_reports_view')")
    ReadingService.Reading shiftReading(@PathVariable long id, Authentication auth, Principal who) {
        var me = orders.whoIs(who.getName());
        var r = readings.reading(id);
        boolean manager = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("PERM_cashier_reports_view"));
        if (r.branchId() != me.branchId() || (!manager && !Long.valueOf(me.userId()).equals(shifts.cashierOf(id)))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That shift was not found.");
        }
        return r;
    }

    /** The picture taken when a shift was opened. Managers only. */
    @GetMapping("/shifts/{id}/photo")
    @PreAuthorize("hasAuthority('PERM_cashier_reports_view')")
    org.springframework.http.ResponseEntity<byte[]> photo(@PathVariable long id, Principal who) {
        byte[] jpeg = shifts.photo(id, orders.whoIs(who.getName()).branchId());
        if (jpeg == null) throw new ApiException(HttpStatus.NOT_FOUND, "There is no photo for that shift.");
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.IMAGE_JPEG)
            .header("Cache-Control", "private, no-store").body(jpeg);
    }

    /** Past shifts for the branch, with over/short. Managers only. */
    @GetMapping("/shifts")
    @PreAuthorize("hasAuthority('PERM_cashier_reports_view')")
    List<ShiftService.HistoryRow> history(Principal who) { return shifts.history(orders.whoIs(who.getName()).branchId()); }

    private static final java.math.BigDecimal MAX = new java.math.BigDecimal("10000000");

    private static long pesos(String s, boolean allowZero, String field) {
        return PesoInput.parse(s, allowZero, MAX, field, "Enter an amount like 500 or 500.50.");
    }
}
