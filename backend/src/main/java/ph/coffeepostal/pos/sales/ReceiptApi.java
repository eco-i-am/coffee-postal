package ph.coffeepostal.pos.sales;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** Everything a printed receipt shows, for printing an order again. Every reprint is counted and audited. */
@RestController
@RequestMapping("/api/branch/orders")
class ReceiptApi {
    record Line(String name, String variant, String addons, long unitPrice, int qty) {}
    record Receipt(String branch, String address, String phone, String tin, boolean vatRegistered,
                   long orderNo, Instant at, String orderType, String cashier, String customerName, String status,
                   List<Line> lines, long subtotal, String discountName, String discountIdNo, long discount, long total,
                   long vatable, long vat, String method, long tendered, long change, String reference, int reprintCount) {}

    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final AuditService audit;

    ReceiptApi(JdbcTemplate jdbc, OrderService orders, AuditService audit) { this.jdbc = jdbc; this.orders = orders; this.audit = audit; }

    @PostMapping("/{id}/reprint")
    @PreAuthorize("hasAuthority('PERM_orders_view')")
    @Transactional
    Receipt reprint(@PathVariable UUID id, Principal who) {
        var me = orders.whoIs(who.getName());
        int n = jdbc.update("UPDATE orders SET reprint_count = reprint_count + 1 WHERE id = ? AND branch_id = ?", id, me.branchId());
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That order was not found.");
        var o = jdbc.queryForMap("""
            SELECT o.order_no, o.created_at, o.order_type, o.status, o.subtotal, o.discount_name, o.discount_id_no, o.discount, o.total,
                   o.vatable, o.vat, o.reprint_count, u.full_name AS cashier, o.customer_name,
                   b.name AS branch, b.address, b.phone, b.tin, b.vat_registered
            FROM orders o JOIN users u ON u.id = o.cashier_id JOIN branches b ON b.id = o.branch_id WHERE o.id = ?
            """, id);
        List<Line> lines = jdbc.query("SELECT name, variant, addons, unit_price, qty FROM order_lines WHERE order_id = ? ORDER BY id",
            (rs, i) -> new Line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5)), id);
        var p = jdbc.queryForMap("SELECT method, amount, tendered, reference FROM order_payments WHERE order_id = ? ORDER BY id LIMIT 1", id);
        long amount = ((Number) p.get("amount")).longValue(), tendered = ((Number) p.get("tendered")).longValue();
        int count = ((Number) o.get("reprint_count")).intValue();
        audit.record(who.getName(), me.branchId(), "RECEIPT_REPRINT", "order", id.toString(), null, "{\"copy\":" + count + "}", null);
        return new Receipt((String) o.get("branch"), (String) o.get("address"), (String) o.get("phone"), (String) o.get("tin"),
            (Boolean) o.get("vat_registered"), ((Number) o.get("order_no")).longValue(), ((java.sql.Timestamp) o.get("created_at")).toInstant(),
            (String) o.get("order_type"), (String) o.get("cashier"), (String) o.get("customer_name"), (String) o.get("status"), lines,
            ((Number) o.get("subtotal")).longValue(), (String) o.get("discount_name"), (String) o.get("discount_id_no"),
            ((Number) o.get("discount")).longValue(), ((Number) o.get("total")).longValue(),
            ((Number) o.get("vatable")).longValue(), ((Number) o.get("vat")).longValue(),
            (String) p.get("method"), tendered, tendered - amount, (String) p.get("reference"), count);
    }
}
