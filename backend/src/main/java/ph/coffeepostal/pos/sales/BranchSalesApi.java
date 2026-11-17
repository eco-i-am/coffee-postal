package ph.coffeepostal.pos.sales;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;

/** Branch dashboard and order list. Always scoped to the signed-in user's branch. Amounts are centavos. */
@RestController
@RequestMapping("/api/branch")
class BranchSalesApi {
    static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    record Payment(String method, long amount, int orders) {}
    record TopItem(String name, long qty, long amount) {}
    record Hour(int hour, long sales) {}
    record OrderRow(String id, long orderNo, Instant createdAt, String orderType, String status,
                    long total, String method, String cashier) {}
    record Dashboard(String date, int orders, long sales, long avgTicket, long discounts, long vat,
                     List<Payment> payments, List<TopItem> top, List<Hour> hourly, List<OrderRow> recent) {}
    record Line(String name, String variant, String addons, long unitPrice, int qty) {}
    record Pay(String method, long amount, long tendered, String reference) {}
    record Refund(String reason, Instant at, String approvedBy) {}
    record OrderDetail(OrderRow order, String discountType, long subtotal, long discount, long vatable, long vat,
                       List<Line> lines, List<Pay> payments, Refund refund) {}

    private final JdbcTemplate jdbc;
    private final OrderService orders;

    BranchSalesApi(JdbcTemplate jdbc, OrderService orders) { this.jdbc = jdbc; this.orders = orders; }

    private static LocalDate parse(String d) {
        if (d == null || d.isBlank()) return LocalDate.now(MANILA);
        try { return LocalDate.parse(d); }
        catch (DateTimeParseException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a valid date."); }
    }

    private static Instant start(LocalDate d) { return d.atStartOfDay(MANILA).toInstant(); }

    private static final String ROW = """
        SELECT o.id, o.order_no, o.created_at, o.order_type, o.status, o.total,
               (SELECT string_agg(DISTINCT p.method, ', ') FROM order_payments p WHERE p.order_id = o.id) AS method,
               u.full_name AS cashier
        FROM orders o JOIN users u ON u.id = o.cashier_id
        """;

    private static OrderRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRow(rs.getString("id"), rs.getLong("order_no"), rs.getTimestamp("created_at").toInstant(),
            rs.getString("order_type"), rs.getString("status"), rs.getLong("total"), rs.getString("method"),
            rs.getString("cashier"));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('PERM_dashboard_view')")
    Dashboard dashboard(@RequestParam(required = false) String date, Principal who) {
        long branch = orders.whoIs(who.getName()).branchId();
        LocalDate day = parse(date);
        var ts = new Object[] { java.sql.Timestamp.from(start(day)), java.sql.Timestamp.from(start(day.plusDays(1))) };

        Map<String, Object> k = jdbc.queryForMap("""
            SELECT count(*) AS n, coalesce(sum(total),0) AS sales, coalesce(sum(discount),0) AS disc, coalesce(sum(vat),0) AS vat
            FROM orders WHERE branch_id = ? AND status = 'PAID' AND created_at >= ? AND created_at < ?
            """, branch, ts[0], ts[1]);
        int n = ((Number) k.get("n")).intValue();
        long sales = ((Number) k.get("sales")).longValue();

        List<Payment> pays = jdbc.query("""
            SELECT p.method, sum(p.amount) AS amount, count(*) AS n FROM order_payments p JOIN orders o ON o.id = p.order_id
            WHERE o.branch_id = ? AND o.status = 'PAID' AND o.created_at >= ? AND o.created_at < ?
            GROUP BY p.method ORDER BY amount DESC
            """, (rs, i) -> new Payment(rs.getString("method"), rs.getLong("amount"), rs.getInt("n")), branch, ts[0], ts[1]);

        List<TopItem> top = jdbc.query("""
            SELECT l.name, sum(l.qty) AS qty, sum(l.unit_price * l.qty) AS amount
            FROM order_lines l JOIN orders o ON o.id = l.order_id
            WHERE o.branch_id = ? AND o.status = 'PAID' AND o.created_at >= ? AND o.created_at < ?
            GROUP BY l.name ORDER BY qty DESC, amount DESC LIMIT 5
            """, (rs, i) -> new TopItem(rs.getString("name"), rs.getLong("qty"), rs.getLong("amount")), branch, ts[0], ts[1]);

        long[] byHour = new long[24];
        jdbc.query("""
            SELECT extract(hour FROM created_at AT TIME ZONE 'Asia/Manila')::int AS h, sum(total) AS s
            FROM orders WHERE branch_id = ? AND status = 'PAID' AND created_at >= ? AND created_at < ? GROUP BY h
            """, rs -> { byHour[rs.getInt("h")] = rs.getLong("s"); }, branch, ts[0], ts[1]);
        List<Hour> hourly = new ArrayList<>();
        for (int h = 6; h <= 22; h++) hourly.add(new Hour(h, byHour[h]));

        List<OrderRow> recent = jdbc.query(ROW + " WHERE o.branch_id = ? AND o.created_at >= ? AND o.created_at < ? ORDER BY o.created_at DESC LIMIT 6",
            (rs, i) -> row(rs), branch, ts[0], ts[1]);

        return new Dashboard(day.toString(), n, sales, n == 0 ? 0 : Math.round((double) sales / n),
            ((Number) k.get("disc")).longValue(), ((Number) k.get("vat")).longValue(), pays, top, hourly, recent);
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('PERM_orders_view')")
    List<OrderRow> list(@RequestParam(required = false) String date, @RequestParam(required = false) String status,
                        @RequestParam(required = false) Long no, Principal who) {
        long branch = orders.whoIs(who.getName()).branchId();
        LocalDate day = parse(date);
        List<Object> args = new ArrayList<>(List.of(branch, java.sql.Timestamp.from(start(day)), java.sql.Timestamp.from(start(day.plusDays(1)))));
        StringBuilder sql = new StringBuilder(ROW + " WHERE o.branch_id = ? AND o.created_at >= ? AND o.created_at < ?");
        if (status != null && !status.isBlank()) {
            if (!List.of("PAID", "REFUNDED", "VOID").contains(status)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown status.");
            sql.append(" AND o.status = ?");
            args.add(status);
        }
        if (no != null) { sql.append(" AND o.order_no = ?"); args.add(no); }
        sql.append(" ORDER BY o.created_at DESC LIMIT 300");
        return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('PERM_orders_view')")
    OrderDetail detail(@PathVariable UUID id, Principal who) {
        long branch = orders.whoIs(who.getName()).branchId();
        List<OrderRow> r = jdbc.query(ROW + " WHERE o.id = ? AND o.branch_id = ?", (rs, i) -> row(rs), id, branch);
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That order was not found.");
        Map<String, Object> o = jdbc.queryForMap("SELECT discount_type, subtotal, discount, vatable, vat FROM orders WHERE id = ?", id);
        List<Line> lines = jdbc.query("SELECT name, variant, addons, unit_price, qty FROM order_lines WHERE order_id = ? ORDER BY id",
            (rs, i) -> new Line(rs.getString("name"), rs.getString("variant"), rs.getString("addons"), rs.getLong("unit_price"), rs.getInt("qty")), id);
        List<Pay> pays = jdbc.query("SELECT method, amount, tendered, reference FROM order_payments WHERE order_id = ? ORDER BY id",
            (rs, i) -> new Pay(rs.getString("method"), rs.getLong("amount"), rs.getLong("tendered"), rs.getString("reference")), id);
        Refund refund = null;
        if ("REFUNDED".equals(r.get(0).status())) {
            Map<String, Object> f = jdbc.queryForMap("""
                SELECT o.refund_reason, o.refunded_at, coalesce(a.full_name, '') AS approver
                FROM orders o LEFT JOIN users a ON a.id = o.refund_approved_by WHERE o.id = ?
                """, id);
            refund = new Refund((String) f.get("refund_reason"), ((java.sql.Timestamp) f.get("refunded_at")).toInstant(), (String) f.get("approver"));
        }
        return new OrderDetail(r.get(0), (String) o.get("discount_type"), ((Number) o.get("subtotal")).longValue(),
            ((Number) o.get("discount")).longValue(), ((Number) o.get("vatable")).longValue(), ((Number) o.get("vat")).longValue(), lines, pays, refund);
    }
}
