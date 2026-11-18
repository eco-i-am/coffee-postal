package ph.coffeepostal.pos.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.reporting.ReportService;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.PesoInput;

/** Things a branch buys locally, outside the HQ purchase order flow (a bag of ice, a missing syrup). */
class OutsourceApis {
    record LineReq(Long itemId, @Size(max = 120) String description, @NotNull BigDecimal qty, @NotBlank String unitCost) {}
    record CreateReq(@NotBlank @Size(max = 120) String vendor, @Size(max = 40) String receiptNo, LocalDate purchasedOn, @NotBlank String paidWith,
                     @Size(max = 200) String note, @NotEmpty @Valid List<LineReq> lines) {}
    record Line(long id, Long itemId, String description, long qty, long unitCost, long amount) {}
    record Purchase(long id, long branchId, String branch, String vendor, String receiptNo, LocalDate purchasedOn, String paidWith, String note,
                    long total, String by, Instant createdAt, List<Line> lines) {}

    @Service
    static class Svc {
        private final JdbcTemplate jdbc;
        private final InventoryService inventory;

        Svc(JdbcTemplate jdbc, InventoryService inventory) { this.jdbc = jdbc; this.inventory = inventory; }

        @Transactional
        long create(long branchId, long userId, CreateReq r) {
            if (!SupplierService.METHODS.contains(r.paidWith())) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick how it was paid.");
            LocalDate on = r.purchasedOn() == null ? LocalDate.now(ZoneId.of("Asia/Manila")) : r.purchasedOn();
            if (on.isAfter(LocalDate.now(ZoneId.of("Asia/Manila")))) throw new ApiException(HttpStatus.BAD_REQUEST, "The purchase date cannot be in the future.");
            long id = jdbc.queryForObject("""
                INSERT INTO outsource_purchases(branch_id, vendor, receipt_no, purchased_on, paid_with, note, total, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?) RETURNING id
                """, Long.class, branchId, r.vendor().trim(), blank(r.receiptNo()), on, r.paidWith(), blank(r.note()), userId);
            long total = 0;
            for (LineReq l : r.lines()) {
                if (l.qty().signum() <= 0 || l.qty().scale() > 3 || l.qty().compareTo(BigDecimal.valueOf(1_000_000)) > 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a quantity like 2 or 2.5.");
                long cost = PesoInput.parse(l.unitCost(), true, "unitCost", "Enter a cost like 120 or 120.50.");
                long qty = l.qty().movePointRight(3).longValueExact();
                String desc = blank(l.description());
                if (l.itemId() != null) {
                    if (!inventory.itemActive(l.itemId())) throw new ApiException(HttpStatus.NOT_FOUND, "An item on this purchase is not available.");
                    if (desc == null) desc = jdbc.queryForObject("SELECT name FROM items WHERE id = ?", String.class, l.itemId());
                }
                if (desc == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Describe what was bought.");
                long amount = BigDecimal.valueOf(qty).multiply(BigDecimal.valueOf(cost)).divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact();
                jdbc.update("INSERT INTO outsource_lines(purchase_id, item_id, description, qty, unit_cost, amount) VALUES (?, ?, ?, ?, ?, ?)",
                    id, l.itemId(), desc, qty, cost, amount);
                if (l.itemId() != null) inventory.move(branchId, l.itemId(), qty, "OUTSOURCE", "Bought from " + r.vendor().trim(), "OUT-" + id, userId);
                total += amount;
            }
            jdbc.update("UPDATE outsource_purchases SET total = ? WHERE id = ?", total, id);
            return id;
        }

        List<Purchase> list(Long branchId, ReportService.Range range) {
            List<Purchase> heads = jdbc.query(HEAD + " WHERE (?::bigint IS NULL OR p.branch_id = ?) AND p.purchased_on BETWEEN ? AND ? ORDER BY p.purchased_on DESC, p.id DESC LIMIT 500",
                (rs, i) -> head(rs), branchId, branchId, java.sql.Date.valueOf(range.from()), java.sql.Date.valueOf(range.to()));
            return heads.stream().map(this::withLines).toList();
        }

        Purchase get(long id, Long branchId) {
            return jdbc.query(HEAD + " WHERE p.id = ? AND (?::bigint IS NULL OR p.branch_id = ?)", (rs, i) -> head(rs), id, branchId, branchId).stream().findFirst()
                .map(this::withLines).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That purchase was not found."));
        }

        private static final String HEAD = """
            SELECT p.id, p.branch_id, b.name, p.vendor, p.receipt_no, p.purchased_on, p.paid_with, p.note, p.total, u.full_name, p.created_at
            FROM outsource_purchases p JOIN branches b ON b.id = p.branch_id JOIN users u ON u.id = p.created_by
            """;

        private static Purchase head(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new Purchase(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getDate(6).toLocalDate(), rs.getString(7),
                rs.getString(8), rs.getLong(9), rs.getString(10), rs.getTimestamp(11).toInstant(), List.of());
        }

        private Purchase withLines(Purchase p) {
            List<Line> lines = jdbc.query("SELECT id, item_id, description, qty, unit_cost, amount FROM outsource_lines WHERE purchase_id = ? ORDER BY id",
                (rs, i) -> new Line(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)), p.id());
            return new Purchase(p.id(), p.branchId(), p.branch(), p.vendor(), p.receiptNo(), p.purchasedOn(), p.paidWith(), p.note(), p.total(), p.by(), p.createdAt(), lines);
        }

        private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    }

    @RestController
    @RequestMapping("/api/branch/outsource")
    static class BranchApi {
        private final Svc svc;
        private final OrderService orders;
        private final AuditService audit;

        BranchApi(Svc svc, OrderService orders, AuditService audit) { this.svc = svc; this.orders = orders; this.audit = audit; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_outsource_view')")
        List<Purchase> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
            return svc.list(orders.whoIs(who.getName()).branchId(), ReportService.range(from, to));
        }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_outsource_view')")
        Purchase one(@PathVariable long id, Principal who) { return svc.get(id, orders.whoIs(who.getName()).branchId()); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_outsource_add')")
        Map<String, Object> create(@Valid @RequestBody CreateReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            long id = svc.create(me.branchId(), me.userId(), r);
            audit.record(who.getName(), me.branchId(), "OUTSOURCE_CREATE", "outsource", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }
    }

    /** HQ: every branch's outsource purchases, read only. */
    @RestController
    @RequestMapping("/api/admin/outsource")
    static class AdminApi {
        private final Svc svc;

        AdminApi(Svc svc) { this.svc = svc; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
        List<Purchase> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) Long branchId) {
            return svc.list(branchId, ReportService.range(from, to));
        }
    }
}
