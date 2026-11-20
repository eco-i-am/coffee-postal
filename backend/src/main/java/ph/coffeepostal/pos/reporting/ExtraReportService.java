package ph.coffeepostal.pos.reporting;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * More read-only reports, with the same rules as {@link ReportService}: an inclusive date range in Manila time, and a branch that is
 * either the signed-in user's own (branch screens) or optional (HQ: null = every branch). Money is centavos. Sales count PAID orders only.
 */
@Service
public class ExtraReportService {
    public record PayRow(String method, String status, int orders, long amount) {}
    public record PayDay(String date, String method, long amount) {}
    public record Payments(List<PayRow> byMethod, List<PayDay> days) {}

    public record DiscountRow(long orderNo, Instant at, String branch, String cashier, String discount, String idNo, long subtotal,
                              long amount, long total, String approvedBy, boolean needsReview, String status) {}
    public record DiscountSum(String discount, int orders, long amount) {}
    public record Discounts(List<DiscountSum> summary, List<DiscountRow> rows) {}

    public record TypeRow(String type, int orders, long sales, long avgOrder, int refunds, long refunded) {}

    public record CashierRow(long id, String name, int orders, long sales, long avgOrder, long discount, int refunds, long refunded,
                             int shifts, long overShort, int offShifts) {}

    public record EmployeeRow(long id, String name, String position, String branch, int days, int shifts, long minutes, int notClockedOut,
                              int flagged, int photoReview) {}

    public record OutsourceRow(String date, String branch, String vendor, String receiptNo, String description, boolean stocked, long qty,
                               long unitCost, long amount, String paidWith) {}

    public record PoPayRow(String paidOn, String branch, long poId, long invoiceId, String method, String reference, long amount, String recordedBy) {}

    public record MyDay(String date, int orders, long sales) {}
    public record MyShift(Instant openedAt, Instant closedAt, long opening, Long expected, Long counted) {}
    public record MyReport(String from, String to, int orders, long sales, long avgOrder, long discount, int refunds, long refunded,
                           List<MyDay> days, List<MyShift> shifts) {}

    private final JdbcTemplate jdbc;

    public ExtraReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Timestamp ts(LocalDate d) { return Timestamp.from(d.atStartOfDay(ReportService.MANILA).toInstant()); }

    /** The range as two timestamps (end is the start of the next day) plus the branch twice, for "? IS NULL OR col = ?". */
    private static Object[] a(ReportService.Range r, Long branchId) {
        return new Object[] { ts(r.from()), ts(r.to().plusDays(1)), branchId, branchId };
    }

    private static final String ORDERS_IN_RANGE = " o.created_at >= ? AND o.created_at < ? AND (?::bigint IS NULL OR o.branch_id = ?)";

    /** Money taken by method, split by whether the order stayed paid or was refunded, and per day. */
    public Payments payments(ReportService.Range r, Long branchId) {
        List<PayRow> by = jdbc.query("""
            SELECT p.method, o.status, count(DISTINCT o.id), coalesce(sum(p.amount), 0)
            FROM orders o JOIN order_payments p ON p.order_id = o.id WHERE""" + ORDERS_IN_RANGE + """
             GROUP BY p.method, o.status ORDER BY p.method, o.status
            """, (rs, i) -> new PayRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4)), a(r, branchId));
        List<PayDay> days = jdbc.query("""
            SELECT (o.created_at AT TIME ZONE 'Asia/Manila')::date AS d, p.method, sum(p.amount)
            FROM orders o JOIN order_payments p ON p.order_id = o.id WHERE o.status = 'PAID' AND""" + ORDERS_IN_RANGE + """
             GROUP BY d, p.method ORDER BY d, p.method
            """, (rs, i) -> new PayDay(rs.getDate(1).toLocalDate().toString(), rs.getString(2), rs.getLong(3)), a(r, branchId));
        return new Payments(by, days);
    }

    /** Every discount given: who, when, to whom (ID number), how much, and who approved it. Newest first, up to 1000. */
    public Discounts discounts(ReportService.Range r, Long branchId) {
        List<DiscountSum> sum = jdbc.query("""
            SELECT coalesce(o.discount_name, o.discount_type), count(*), coalesce(sum(o.discount), 0)
            FROM orders o WHERE o.status = 'PAID' AND o.discount_type <> 'NONE' AND""" + ORDERS_IN_RANGE + """
             GROUP BY 1 ORDER BY 3 DESC
            """, (rs, i) -> new DiscountSum(rs.getString(1), rs.getInt(2), rs.getLong(3)), a(r, branchId));
        List<DiscountRow> rows = jdbc.query("""
            SELECT o.order_no, o.created_at, b.name, u.full_name, coalesce(o.discount_name, o.discount_type), o.discount_id_no,
                   o.subtotal, o.discount, o.total, ap.full_name, o.approval_flag, o.status
            FROM orders o JOIN branches b ON b.id = o.branch_id JOIN users u ON u.id = o.cashier_id
            LEFT JOIN users ap ON ap.id = o.discount_approved_by
            WHERE o.discount_type <> 'NONE' AND""" + ORDERS_IN_RANGE + """
             ORDER BY o.created_at DESC, o.id DESC LIMIT 1000
            """, (rs, i) -> new DiscountRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getString(10), rs.getBoolean(11), rs.getString(12)), a(r, branchId));
        return new Discounts(sum, rows);
    }

    /** Sales by dine-in, takeout and delivery. All three are always returned. */
    public List<TypeRow> orderTypes(ReportService.Range r, Long branchId) {
        Map<String, TypeRow> found = new LinkedHashMap<>();
        jdbc.query("""
            SELECT o.order_type,
                   count(*) FILTER (WHERE o.status = 'PAID'),
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'PAID'), 0),
                   count(*) FILTER (WHERE o.status = 'REFUNDED'),
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'REFUNDED'), 0)
            FROM orders o WHERE""" + ORDERS_IN_RANGE + " GROUP BY o.order_type", rs -> {
                int n = rs.getInt(2); long s = rs.getLong(3);
                found.put(rs.getString(1), new TypeRow(rs.getString(1), n, s, n == 0 ? 0 : Math.round((double) s / n), rs.getInt(4), rs.getLong(5)));
            }, a(r, branchId));
        List<TypeRow> out = new ArrayList<>();
        for (String t : List.of("DINE_IN", "TAKEOUT", "DELIVERY")) out.add(found.getOrDefault(t, new TypeRow(t, 0, 0, 0, 0, 0)));
        return out;
    }

    /** Per cashier: what they sold, refunded and discounted, and how their drawer counts came out (counted minus expected, closed shifts). */
    public List<CashierRow> cashiers(ReportService.Range r, Long branchId) {
        Map<Long, long[]> nums = new LinkedHashMap<>();   // orders, sales, discount, refunds, refunded, shifts, overShort, offShifts
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.query("""
            SELECT u.id, u.full_name,
                   count(*) FILTER (WHERE o.status = 'PAID'),
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'PAID'), 0),
                   coalesce(sum(o.discount) FILTER (WHERE o.status = 'PAID'), 0),
                   count(*) FILTER (WHERE o.status = 'REFUNDED'),
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'REFUNDED'), 0)
            FROM orders o JOIN users u ON u.id = o.cashier_id WHERE""" + ORDERS_IN_RANGE + " GROUP BY u.id, u.full_name", rs -> {
                long id = rs.getLong(1);
                names.put(id, rs.getString(2));
                nums.put(id, new long[] { rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), 0, 0, 0 });
            }, a(r, branchId));
        jdbc.query("""
            SELECT u.id, u.full_name, count(*), coalesce(sum(s.counted_cash - s.expected_cash), 0),
                   count(*) FILTER (WHERE s.counted_cash <> s.expected_cash)
            FROM shifts s JOIN users u ON u.id = s.cashier_id
            WHERE s.closed_at IS NOT NULL AND s.counted_cash IS NOT NULL AND s.closed_at >= ? AND s.closed_at < ? AND (?::bigint IS NULL OR s.branch_id = ?)
            GROUP BY u.id, u.full_name
            """, rs -> {
                long id = rs.getLong(1);
                names.putIfAbsent(id, rs.getString(2));
                long[] n = nums.computeIfAbsent(id, k -> new long[8]);
                n[5] = rs.getLong(3); n[6] = rs.getLong(4); n[7] = rs.getLong(5);
            }, a(r, branchId));
        List<CashierRow> out = new ArrayList<>();
        nums.forEach((id, n) -> out.add(new CashierRow(id, names.get(id), (int) n[0], n[1], n[0] == 0 ? 0 : Math.round((double) n[1] / n[0]), n[2],
            (int) n[3], n[4], (int) n[5], n[6], (int) n[7])));
        out.sort((x, y) -> Long.compare(y.sales(), x.sales()));
        return out;
    }

    /**
     * Attendance per employee from the time clock. A shift is a time in followed by the next time out; minutes add those up.
     * "Not clocked out" is a time in with no time out after it (still working, or forgot). Flagged = punches with a wrong tablet clock
     * or typed in by a manager; photo review = punches whose face did not match or could not be seen.
     */
    public List<EmployeeRow> employees(ReportService.Range r, Long branchId) {
        Timestamp f = ts(r.from()), t = ts(r.to().plusDays(1));
        return jdbc.query("""
            WITH seq AS (
              SELECT l.employee_id, l.branch_id, l.kind, l.punched_at, l.flag, l.face_status,
                     lead(l.kind) OVER w AS next_kind, lead(l.punched_at) OVER w AS next_at
              FROM attendance_logs l
              WHERE l.punched_at >= ?::timestamptz - interval '1 day' AND l.punched_at < ?::timestamptz + interval '1 day'
                AND (?::bigint IS NULL OR l.branch_id = ?)
              WINDOW w AS (PARTITION BY l.employee_id ORDER BY l.punched_at, l.id)
            )
            SELECT e.id, e.full_name, coalesce(e.position, ''), coalesce(b.name, 'Head office'),
                   count(DISTINCT (s.punched_at AT TIME ZONE 'Asia/Manila')::date) FILTER (WHERE s.kind = 'IN' AND s.punched_at >= ? AND s.punched_at < ?),
                   count(*) FILTER (WHERE s.kind = 'IN' AND s.punched_at >= ? AND s.punched_at < ?),
                   coalesce(sum(extract(epoch FROM (s.next_at - s.punched_at)) / 60) FILTER (WHERE s.kind = 'IN' AND s.next_kind = 'OUT' AND s.punched_at >= ? AND s.punched_at < ?), 0)::bigint,
                   count(*) FILTER (WHERE s.kind = 'IN' AND (s.next_kind IS NULL OR s.next_kind = 'IN') AND s.punched_at >= ? AND s.punched_at < ?),
                   count(*) FILTER (WHERE s.flag IS NOT NULL AND s.punched_at >= ? AND s.punched_at < ?),
                   count(*) FILTER (WHERE s.face_status IN ('REVIEW', 'NO_FACE') AND s.punched_at >= ? AND s.punched_at < ?)
            FROM seq s JOIN employees e ON e.id = s.employee_id LEFT JOIN branches b ON b.id = s.branch_id
            GROUP BY e.id, e.full_name, e.position, b.name
            HAVING count(*) FILTER (WHERE s.punched_at >= ? AND s.punched_at < ?) > 0
            ORDER BY lower(e.full_name)
            """, (rs, i) -> new EmployeeRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getLong(7),
                rs.getInt(8), rs.getInt(9), rs.getInt(10)),
            f, t, branchId, branchId, f, t, f, t, f, t, f, t, f, t, f, t, f, t);
    }

    /** Purchases from outside suppliers, line by line, up to 1000. */
    public List<OutsourceRow> outsource(ReportService.Range r, Long branchId) {
        return jdbc.query("""
            SELECT p.purchased_on, b.name, p.vendor, p.receipt_no, l.description, l.item_id IS NOT NULL, l.qty, l.unit_cost, l.amount, p.paid_with
            FROM outsource_lines l JOIN outsource_purchases p ON p.id = l.purchase_id JOIN branches b ON b.id = p.branch_id
            WHERE p.purchased_on BETWEEN ? AND ? AND (?::bigint IS NULL OR p.branch_id = ?)
            ORDER BY p.purchased_on DESC, p.id DESC, l.id LIMIT 1000
            """, (rs, i) -> new OutsourceRow(rs.getDate(1).toLocalDate().toString(), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBoolean(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getString(10)),
            Date.valueOf(r.from()), Date.valueOf(r.to()), branchId, branchId);
    }

    /** Payments branches made to HQ against their purchase order invoices, newest first, up to 1000. */
    public List<PoPayRow> poPayments(ReportService.Range r, Long branchId) {
        return jdbc.query("""
            SELECT ip.paid_on, b.name, i.po_id, i.id, ip.method, ip.reference, ip.amount, u.full_name
            FROM invoice_payments ip JOIN invoices i ON i.id = ip.invoice_id JOIN branches b ON b.id = i.branch_id JOIN users u ON u.id = ip.recorded_by
            WHERE ip.paid_on BETWEEN ? AND ? AND (?::bigint IS NULL OR i.branch_id = ?)
            ORDER BY ip.paid_on DESC, ip.id DESC LIMIT 1000
            """, (rs, i) -> new PoPayRow(rs.getDate(1).toLocalDate().toString(), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getLong(7), rs.getString(8)),
            Date.valueOf(r.from()), Date.valueOf(r.to()), branchId, branchId);
    }

    /** A cashier's own sales and drawer counts. Only ever for the signed-in user. */
    public MyReport mine(ReportService.Range r, long userId, long branchId) {
        Timestamp f = ts(r.from()), t = ts(r.to().plusDays(1));
        Map<String, Object> k = jdbc.queryForMap("""
            SELECT count(*) FILTER (WHERE o.status = 'PAID') AS n,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'PAID'), 0) AS sales,
                   coalesce(sum(o.discount) FILTER (WHERE o.status = 'PAID'), 0) AS disc,
                   count(*) FILTER (WHERE o.status = 'REFUNDED') AS refunds,
                   coalesce(sum(o.total) FILTER (WHERE o.status = 'REFUNDED'), 0) AS refunded
            FROM orders o WHERE o.cashier_id = ? AND o.branch_id = ? AND o.created_at >= ? AND o.created_at < ?
            """, userId, branchId, f, t);
        Map<String, MyDay> byDate = new LinkedHashMap<>();
        jdbc.query("""
            SELECT (o.created_at AT TIME ZONE 'Asia/Manila')::date, count(*), sum(o.total)
            FROM orders o WHERE o.status = 'PAID' AND o.cashier_id = ? AND o.branch_id = ? AND o.created_at >= ? AND o.created_at < ?
            GROUP BY 1
            """, rs -> { String d = rs.getDate(1).toLocalDate().toString(); byDate.put(d, new MyDay(d, rs.getInt(2), rs.getLong(3))); },
            userId, branchId, f, t);
        List<MyDay> days = new ArrayList<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) days.add(byDate.getOrDefault(d.toString(), new MyDay(d.toString(), 0, 0)));
        List<MyShift> shifts = jdbc.query("""
            SELECT opened_at, closed_at, opening_cash, expected_cash, counted_cash FROM shifts
            WHERE cashier_id = ? AND branch_id = ? AND opened_at >= ? AND opened_at < ? ORDER BY opened_at DESC LIMIT 60
            """, (rs, i) -> new MyShift(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                rs.getLong(3), (Long) rs.getObject(4), (Long) rs.getObject(5)), userId, branchId, f, t);
        int n = ((Number) k.get("n")).intValue();
        long sales = ((Number) k.get("sales")).longValue();
        return new MyReport(r.from().toString(), r.to().toString(), n, sales, n == 0 ? 0 : Math.round((double) sales / n), ((Number) k.get("disc")).longValue(),
            ((Number) k.get("refunds")).intValue(), ((Number) k.get("refunded")).longValue(), days, shifts);
    }
}
