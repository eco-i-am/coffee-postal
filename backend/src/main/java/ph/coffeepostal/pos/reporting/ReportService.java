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
    public record StockRow(long itemId, String name, String unit, long beginning, long received, long transferIn, long transferOut,
                           long used, long wasted, long adjusted, long ending, long value) {}
    public record PriceRow(long productId, String name, String category, long basePrice, long price, boolean available, int branchOverrides) {}
    public record PoRow(long id, long branchId, String branch, String status, java.time.Instant requestedAt, int lines, long value) {}
    public record PoItemRow(String name, String unit, long ordered, long approved, long received, long value) {}
    public record CatAmount(String category, long amount) {}
    /** Profit and loss. Sales exclude VAT. Cost of goods = stock used in sales x the item's current cost. */
    public record ProfitLoss(String from, String to, int orders, long grossSales, long discounts, long vat, long netSales, long costOfGoods, long waste,
                             long grossProfit, List<CatAmount> expenses, long expensesTotal, long outsource, long netProfit) {}
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

    /**
     * Stock and beginning/ending inventory, as of the range: beginning + received + transfers in - transfers out - used - wasted
     * + corrections = ending. Quantities are thousandths of a unit; value is ending stock at the item's current cost.
     * branchId null = every branch added together (HQ).
     */
    public List<StockRow> stock(Range r, Long branchId) {
        Timestamp f = ts(r.from()), t = ts(r.to().plusDays(1));
        return jdbc.query("""
            SELECT i.id, i.name, i.unit, i.cost,
              coalesce(sum(m.qty) FILTER (WHERE m.created_at < ?), 0) AS beginning,
              coalesce(sum(m.qty) FILTER (WHERE m.kind = 'PO_RECEIVE' AND m.created_at >= ? AND m.created_at < ?), 0) AS received,
              coalesce(sum(m.qty) FILTER (WHERE m.kind = 'TRANSFER_IN' AND m.created_at >= ? AND m.created_at < ?), 0) AS t_in,
              coalesce(-sum(m.qty) FILTER (WHERE m.kind = 'TRANSFER_OUT' AND m.created_at >= ? AND m.created_at < ?), 0) AS t_out,
              coalesce(-sum(m.qty) FILTER (WHERE m.kind = 'SALE' AND m.created_at >= ? AND m.created_at < ?), 0) AS used,
              coalesce(-sum(m.qty) FILTER (WHERE m.kind = 'WASTE' AND m.created_at >= ? AND m.created_at < ?), 0) AS wasted,
              coalesce(sum(m.qty) FILTER (WHERE m.kind IN ('ADJUST','OPENING') AND m.created_at >= ? AND m.created_at < ?), 0) AS adjusted,
              coalesce(sum(m.qty) FILTER (WHERE m.created_at < ?), 0) AS ending
            FROM items i LEFT JOIN stock_movements m ON m.item_id = i.id AND (?::bigint IS NULL OR m.branch_id = ?)
            WHERE i.active OR EXISTS (SELECT 1 FROM stock_movements x WHERE x.item_id = i.id AND (?::bigint IS NULL OR x.branch_id = ?))
            GROUP BY i.id, i.name, i.unit, i.cost ORDER BY lower(i.name)
            """, (rs, i) -> {
                long ending = rs.getLong("ending");
                long value = java.math.BigDecimal.valueOf(ending).multiply(java.math.BigDecimal.valueOf(rs.getLong("cost")))
                    .divide(java.math.BigDecimal.valueOf(1000), 0, java.math.RoundingMode.HALF_UP).longValueExact();
                return new StockRow(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getLong("beginning"), rs.getLong("received"),
                    rs.getLong("t_in"), rs.getLong("t_out"), rs.getLong("used"), rs.getLong("wasted"), rs.getLong("adjusted"), ending, value);
            }, f, f, t, f, t, f, t, f, t, f, t, f, t, t, branchId, branchId, branchId, branchId);
    }

    private static long thousandthsToCentavos(long qtyTimesCost) {
        return java.math.BigDecimal.valueOf(qtyTimesCost).divide(java.math.BigDecimal.valueOf(1000), 0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * Profit and loss for the range. branchId null = every branch plus HQ's own expenses.
     * Outsource purchases of stocked items are not an expense here (the stock they add reaches cost of goods when it is used);
     * only the lines that are not stocked items count.
     */
    public ProfitLoss profitLoss(Range r, Long branchId) {
        Timestamp f = ts(r.from()), t = ts(r.to().plusDays(1));
        Map<String, Object> s = jdbc.queryForMap("""
            SELECT count(*) AS n, coalesce(sum(o.subtotal), 0) AS gross, coalesce(sum(o.discount), 0) AS disc,
                   coalesce(sum(o.vat), 0) AS vat, coalesce(sum(o.total), 0) AS total
            FROM orders o WHERE o.status = 'PAID' AND o.created_at >= ? AND o.created_at < ? AND (?::bigint IS NULL OR o.branch_id = ?)
            """, f, t, branchId, branchId);
        long used = jdbc.queryForObject("""
            SELECT coalesce(sum(-m.qty * i.cost), 0) FROM stock_movements m JOIN items i ON i.id = m.item_id
            WHERE m.kind = 'SALE' AND m.created_at >= ? AND m.created_at < ? AND (?::bigint IS NULL OR m.branch_id = ?)
            """, Long.class, f, t, branchId, branchId);
        long wasted = jdbc.queryForObject("""
            SELECT coalesce(sum(-m.qty * i.cost), 0) FROM stock_movements m JOIN items i ON i.id = m.item_id
            WHERE m.kind = 'WASTE' AND m.created_at >= ? AND m.created_at < ? AND (?::bigint IS NULL OR m.branch_id = ?)
            """, Long.class, f, t, branchId, branchId);
        List<CatAmount> cats = jdbc.query("""
            SELECT c.name, sum(e.amount) FROM expenses e JOIN expense_categories c ON c.id = e.category_id
            WHERE e.voided_at IS NULL AND e.expense_date BETWEEN ? AND ? AND (?::bigint IS NULL OR e.branch_id = ?)
            GROUP BY c.name ORDER BY sum(e.amount) DESC
            """, (rs, i) -> new CatAmount(rs.getString(1), rs.getLong(2)), java.sql.Date.valueOf(r.from()), java.sql.Date.valueOf(r.to()), branchId, branchId);
        long expenses = cats.stream().mapToLong(CatAmount::amount).sum();
        long outsource = jdbc.queryForObject("""
            SELECT coalesce(sum(l.amount), 0) FROM outsource_lines l JOIN outsource_purchases p ON p.id = l.purchase_id
            WHERE l.item_id IS NULL AND p.purchased_on BETWEEN ? AND ? AND (?::bigint IS NULL OR p.branch_id = ?)
            """, Long.class, java.sql.Date.valueOf(r.from()), java.sql.Date.valueOf(r.to()), branchId, branchId);
        long total = ((Number) s.get("total")).longValue(), vat = ((Number) s.get("vat")).longValue();
        long netSales = total - vat, cogs = thousandthsToCentavos(used), waste = thousandthsToCentavos(wasted);
        long gross = netSales - cogs - waste;
        return new ProfitLoss(r.from().toString(), r.to().toString(), ((Number) s.get("n")).intValue(), ((Number) s.get("gross")).longValue(),
            ((Number) s.get("disc")).longValue(), vat, netSales, cogs, waste, gross, cats, expenses, outsource, gross - expenses - outsource);
    }

    /**
     * Selling prices. With a branch: what that branch charges and whether it sells the product. Without: the HQ price, and how many
     * branches charge something different.
     */
    public List<PriceRow> prices(Long branchId) {
        return jdbc.query("""
            SELECT p.id, p.name, coalesce(c.name, ''), p.price,
                   coalesce(bp.price, p.price) AS price, coalesce(bp.available, TRUE) AS available,
                   (SELECT count(*) FROM branch_products x WHERE x.product_id = p.id AND x.price IS NOT NULL AND x.price <> p.price) AS overrides
            FROM products p LEFT JOIN product_categories c ON c.id = p.category_id
            LEFT JOIN branch_products bp ON bp.product_id = p.id AND bp.branch_id = ?
            WHERE p.active ORDER BY lower(coalesce(c.name, '')), lower(p.name)
            """, (rs, i) -> new PriceRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getBoolean(6), rs.getInt(7)),
            branchId);
    }

    /** Purchase orders requested in the range, with what they are worth (received if it has arrived, else approved, else ordered). */
    public List<PoRow> branchPos(Range r, Long branchId) {
        return jdbc.query("""
            SELECT p.id, p.branch_id, b.name, p.status, p.requested_at,
                   (SELECT count(*) FROM branch_po_lines l WHERE l.po_id = p.id),
                   (SELECT coalesce(sum(coalesce(l.qty_received, l.qty_approved, l.qty_ordered) * l.unit_cost), 0) FROM branch_po_lines l WHERE l.po_id = p.id)
            FROM branch_pos p JOIN branches b ON b.id = p.branch_id
            WHERE p.requested_at >= ? AND p.requested_at < ? AND (?::bigint IS NULL OR p.branch_id = ?)
            ORDER BY p.requested_at DESC, p.id DESC LIMIT 1000
            """, (rs, i) -> new PoRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getInt(6), rs.getLong(7)),
            ts(r.from()), ts(r.to().plusDays(1)), branchId, branchId);
    }

    /** What HQ sold to branches through purchase orders (rejected and cancelled orders left out), by item. */
    public List<PoItemRow> poItems(Range r, Long branchId) {
        return jdbc.query("""
            SELECT l.name, l.unit, sum(l.qty_ordered), coalesce(sum(l.qty_approved), 0), coalesce(sum(l.qty_received), 0),
                   coalesce(sum(coalesce(l.qty_received, 0) * l.unit_cost), 0)
            FROM branch_po_lines l JOIN branch_pos p ON p.id = l.po_id
            WHERE p.status NOT IN ('REJECTED','CANCELLED') AND p.requested_at >= ? AND p.requested_at < ? AND (?::bigint IS NULL OR p.branch_id = ?)
            GROUP BY l.name, l.unit ORDER BY 6 DESC, l.name
            """, (rs, i) -> new PoItemRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)),
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
