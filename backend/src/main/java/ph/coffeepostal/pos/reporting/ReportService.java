package ph.coffeepostal.pos.reporting;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ph.coffeepostal.pos.finance.InvoiceService;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * Read-only reports. Every method takes a date range (inclusive, Manila time) and an optional branch:
 * a branch user always passes their own branch; HQ may pass one or leave it null for all branches.
 * Sales figures count PAID orders only. Refunded orders are reported separately. Money is centavos.
 */
@Service
public class ReportService {
    static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    static final int MAX_DAYS = 366;

    public record Range(LocalDate from, LocalDate to) {}
    public record Day(String date, int orders, long sales, long discount, long vat, int refunds, long refunded) {}
    public record Totals(int orders, long sales, long avgOrder, long discount, long vatable, long vat, int refunds, long refunded) {}
    public record Pay(String method, int orders, long amount) {}
    public record Cashier(String cashier, int orders, long sales, int refunds) {}
    public record Disc(String type, String name, int orders, long amount) {}
    public record Sales(String from, String to, List<Day> days, Totals totals, List<Pay> byPayment, List<Cashier> byCashier, List<Disc> discounts) {}
    public record ProductRow(String name, long qty, long sales) {}
    public record StockRow(long itemId, String name, String unit, long received, long used, long wasted, long adjusted, long onHand, long value) {}
    public record BranchRow(long branchId, String branch, int orders, long sales, long avgOrder, int refunds, long refunded) {}
    public record AgingRow(long branchId, String branch, long current, long d1to30, long d31to60, long d61plus, long total) {}

    private final JdbcTemplate jdbc;
    private final InvoiceService invoices;

    public ReportService(JdbcTemplate jdbc, InvoiceService invoices) { this.jdbc = jdbc; this.invoices = invoices; }

    /** Blank = the last 7 days ending today. */
    public static Range range(String from, String to) {
        try {
            LocalDate t = to == null || to.isBlank() ? LocalDate.now(MANILA) : LocalDate.parse(to);
            LocalDate f = from == null || from.isBlank() ? t.minusDays(6) : LocalDate.parse(from);
            if (f.isAfter(t)) throw new ApiException(HttpStatus.BAD_REQUEST, "The start date must not be after the end date.");
            if (f.plusDays(MAX_DAYS).isBefore(t)) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a range of one year or less.");
            return new Range(f, t);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick valid dates.");
        }
    }

    private static Timestamp ts(LocalDate d) { return Timestamp.from(d.atStartOfDay(MANILA).toInstant()); }

    /** " AND created_at in range [AND branch]" plus its arguments, for a table aliased o. */
    private static String where(Long branchId) { return " o.created_at >= ? AND o.created_at < ?" + (branchId != null ? " AND o.branch_id = ?" : ""); }

    private static Object[] args(Range r, Long branchId) {
        return branchId != null ? new Object[] { ts(r.from()), ts(r.to().plusDays(1)), branchId }
                                : new Object[] { ts(r.from()), ts(r.to().plusDays(1)) };
    }

    public Sales sales(Range r, Long branchId) {
        Map<String, Day> byDate = new HashMap<>();
        jdbc.query("""
            SELECT (o.created_at AT TIME ZONE 'Asia/Manila')::date AS d,
                   count(*) FILTER (WHERE o.status = 'PAID') AS orders,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'PAID'), 0) AS sales,
                   coalesce(sum(o.discount) FILTER (WHERE o.status = 'PAID'), 0) AS discount,
                   coalesce(sum(o.vat) FILTER (WHERE o.status = 'PAID'), 0) AS vat,
                   count(*) FILTER (WHERE o.status = 'REFUNDED') AS refunds,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'REFUNDED'), 0) AS refunded
            FROM orders o WHERE""" + where(branchId) + " GROUP BY d", rs -> {
                String d = rs.getDate("d").toLocalDate().toString();
                byDate.put(d, new Day(d, rs.getInt("orders"), rs.getLong("sales"), rs.getLong("discount"), rs.getLong("vat"),
                    rs.getInt("refunds"), rs.getLong("refunded")));
            }, args(r, branchId));

        List<Day> days = new ArrayList<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            days.add(byDate.getOrDefault(d.toString(), new Day(d.toString(), 0, 0, 0, 0, 0, 0)));
        }

        Map<String, Object> t = jdbc.queryForMap("""
            SELECT count(*) FILTER (WHERE o.status = 'PAID') AS orders,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'PAID'), 0) AS sales,
                   coalesce(sum(o.discount) FILTER (WHERE o.status = 'PAID'), 0) AS discount,
                   coalesce(sum(o.vatable) FILTER (WHERE o.status = 'PAID'), 0) AS vatable,
                   coalesce(sum(o.vat) FILTER (WHERE o.status = 'PAID'), 0) AS vat,
                   count(*) FILTER (WHERE o.status = 'REFUNDED') AS refunds,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'REFUNDED'), 0) AS refunded
            FROM orders o WHERE""" + where(branchId), args(r, branchId));
        int n = ((Number) t.get("orders")).intValue();
        long sales = ((Number) t.get("sales")).longValue();
        Totals totals = new Totals(n, sales, n == 0 ? 0 : Math.round((double) sales / n), ((Number) t.get("discount")).longValue(),
            ((Number) t.get("vatable")).longValue(), ((Number) t.get("vat")).longValue(),
            ((Number) t.get("refunds")).intValue(), ((Number) t.get("refunded")).longValue());

        List<Pay> pays = jdbc.query("""
            SELECT p.method, count(DISTINCT o.id) AS n, sum(p.amount) AS amount
            FROM orders o JOIN order_payments p ON p.order_id = o.id WHERE o.status = 'PAID' AND""" + where(branchId)
            + " GROUP BY p.method ORDER BY amount DESC", (rs, i) -> new Pay(rs.getString(1), rs.getInt(2), rs.getLong(3)), args(r, branchId));

        List<Cashier> cashiers = jdbc.query("""
            SELECT u.full_name,
                   count(*) FILTER (WHERE o.status = 'PAID') AS orders,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'PAID'), 0) AS sales,
                   count(*) FILTER (WHERE o.status = 'REFUNDED') AS refunds
            FROM orders o JOIN users u ON u.id = o.cashier_id WHERE""" + where(branchId)
            + " GROUP BY u.id, u.full_name ORDER BY sales DESC", (rs, i) -> new Cashier(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getInt(4)), args(r, branchId));

        List<Disc> discs = jdbc.query("""
            SELECT o.discount_type, coalesce(max(o.discount_name), o.discount_type) AS name, count(*) AS n, sum(o.discount) AS amount FROM orders o
            WHERE o.status = 'PAID' AND o.discount_type <> 'NONE' AND""" + where(branchId)
            + " GROUP BY o.discount_type ORDER BY amount DESC", (rs, i) -> new Disc(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4)), args(r, branchId));

        return new Sales(r.from().toString(), r.to().toString(), days, totals, pays, cashiers, discs);
    }

    /** Products by units sold. Sales = price x qty before any order-level discount. */
    public List<ProductRow> products(Range r, Long branchId) {
        return jdbc.query("""
            SELECT l.name, sum(l.qty) AS qty, sum(l.unit_price * l.qty) AS sales
            FROM order_lines l JOIN orders o ON o.id = l.order_id WHERE o.status = 'PAID' AND""" + where(branchId)
            + " GROUP BY l.name ORDER BY qty DESC, sales DESC LIMIT 200",
            (rs, i) -> new ProductRow(rs.getString(1), rs.getLong(2), rs.getLong(3)), args(r, branchId));
    }

    /** What moved in the range, and what is on hand now (all quantities in thousandths of a unit). */
    public List<StockRow> stock(Range r, long branchId) {
        return jdbc.query("""
            SELECT i.id, i.name, i.unit, i.cost,
              coalesce(sum(m.qty) FILTER (WHERE m.kind = 'PO_RECEIVE' AND m.created_at >= ? AND m.created_at < ?), 0) AS received,
              coalesce(-sum(m.qty) FILTER (WHERE m.kind = 'SALE' AND m.created_at >= ? AND m.created_at < ?), 0) AS used,
              coalesce(-sum(m.qty) FILTER (WHERE m.kind = 'WASTE' AND m.created_at >= ? AND m.created_at < ?), 0) AS wasted,
              coalesce(sum(m.qty) FILTER (WHERE m.kind = 'ADJUST' AND m.created_at >= ? AND m.created_at < ?), 0) AS adjusted,
              coalesce(sum(m.qty), 0) AS on_hand
            FROM items i LEFT JOIN stock_movements m ON m.item_id = i.id AND m.branch_id = ?
            WHERE i.active OR EXISTS (SELECT 1 FROM stock_movements x WHERE x.item_id = i.id AND x.branch_id = ?)
            GROUP BY i.id, i.name, i.unit, i.cost ORDER BY lower(i.name)
            """, (rs, i) -> {
                long onHand = rs.getLong("on_hand");
                long value = java.math.BigDecimal.valueOf(onHand).multiply(java.math.BigDecimal.valueOf(rs.getLong("cost")))
                    .divide(java.math.BigDecimal.valueOf(1000), 0, java.math.RoundingMode.HALF_UP).longValueExact();
                return new StockRow(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getLong("received"),
                    rs.getLong("used"), rs.getLong("wasted"), rs.getLong("adjusted"), onHand, value);
            }, ts(r.from()), ts(r.to().plusDays(1)), ts(r.from()), ts(r.to().plusDays(1)), ts(r.from()), ts(r.to().plusDays(1)),
            ts(r.from()), ts(r.to().plusDays(1)), branchId, branchId);
    }

    /** HQ: every open branch side by side. */
    public List<BranchRow> branches(Range r) {
        return jdbc.query("""
            SELECT b.id, b.name,
                   count(o.id) FILTER (WHERE o.status = 'PAID') AS orders,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'PAID'), 0) AS sales,
                   count(o.id) FILTER (WHERE o.status = 'REFUNDED') AS refunds,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'REFUNDED'), 0) AS refunded
            FROM branches b LEFT JOIN orders o ON o.branch_id = b.id AND o.created_at >= ? AND o.created_at < ?
            WHERE b.active GROUP BY b.id, b.name ORDER BY sales DESC, b.name
            """, (rs, i) -> {
                int n = rs.getInt(3); long s = rs.getLong(4);
                return new BranchRow(rs.getLong(1), rs.getString(2), n, s, n == 0 ? 0 : Math.round((double) s / n), rs.getInt(5), rs.getLong(6));
            }, ts(r.from()), ts(r.to().plusDays(1)));
    }

    /** HQ: how overdue the unpaid invoices are, per branch. Not date-range based: it is "as of today". */
    public List<AgingRow> aging() {
        Map<Long, long[]> sums = new java.util.LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (InvoiceService.Row row : invoices.list(null, "open")) {
            long[] b = sums.computeIfAbsent(row.branchId(), k -> new long[4]);
            names.put(row.branchId(), row.branch());
            long d = row.daysOverdue();
            int slot = !row.overdue() ? 0 : d <= 30 ? 1 : d <= 60 ? 2 : 3;
            b[slot] += row.balance();
        }
        List<AgingRow> out = new ArrayList<>();
        sums.forEach((id, b) -> out.add(new AgingRow(id, names.get(id), b[0], b[1], b[2], b[3], b[0] + b[1] + b[2] + b[3])));
        out.sort((x, y) -> Long.compare(y.total(), x.total()));
        return out;
    }
}
