package ph.coffeepostal.pos.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;

/** Invoices from HQ to branches (receivables for HQ, payables for the branch). Money is centavos. */
@Service
public class InvoiceService {
    static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    static final List<String> METHODS = List.of("CASH", "BANK", "GCASH", "MAYA", "CHECK");

    public record Row(long id, long poId, long branchId, String branch, Instant issuedAt, LocalDate dueDate,
                      long amount, long paid, long balance, String status, boolean overdue, long daysOverdue) {}
    public record Line(String name, String unit, long qty, long unitCost) {}
    public record Payment(long id, long amount, String method, String reference, LocalDate paidOn, String recordedBy) {}
    public record Detail(Row invoice, List<Line> lines, List<Payment> payments) {}
    public record BranchOwing(long branchId, String branch, long outstanding, long overdue, int open) {}
    public record Summary(long outstanding, long overdue, int openCount, int overdueCount, List<BranchOwing> byBranch) {}

    private final JdbcTemplate jdbc;

    public InvoiceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String TODAY = "(now() AT TIME ZONE 'Asia/Manila')::date";

    /** Base query: one row per invoice with what has been paid so far. */
    private static final String BASE = """
        SELECT i.id, i.po_id, i.branch_id, b.name AS branch, i.issued_at, i.due_date, i.amount,
               coalesce((SELECT sum(p.amount) FROM invoice_payments p WHERE p.invoice_id = i.id), 0) AS paid,
               (i.due_date - %s) AS days_left
        FROM invoices i JOIN branches b ON b.id = i.branch_id
        """.formatted(TODAY);

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        long amount = rs.getLong("amount"), paid = rs.getLong("paid"), balance = amount - paid;
        long daysLeft = rs.getLong("days_left");
        boolean overdue = balance > 0 && daysLeft < 0;
        String status = balance == 0 ? "PAID" : paid > 0 ? "PARTIAL" : "UNPAID";
        return new Row(rs.getLong("id"), rs.getLong("po_id"), rs.getLong("branch_id"), rs.getString("branch"),
            rs.getTimestamp("issued_at").toInstant(), rs.getDate("due_date").toLocalDate(), amount, paid, balance,
            status, overdue, overdue ? -daysLeft : 0);
    }

    /** filter: open (still owed), overdue, paid, or all. branchId non-null limits to one branch. */
    public List<Row> list(Long branchId, String filter) { return list(branchId, filter, null); }

    /** limit null = every row (totals and aging need all of them); screens pass a page size. */
    public List<Row> list(Long branchId, String filter, Integer limit) {
        String where = switch (filter == null || filter.isBlank() ? "all" : filter) {
            case "open" -> " WHERE t.amount - t.paid > 0";
            case "overdue" -> " WHERE t.amount - t.paid > 0 AND t.days_left < 0";
            case "paid" -> " WHERE t.amount - t.paid = 0";
            case "all" -> "";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown filter.");
        };
        String branchSql = branchId != null ? " WHERE i.branch_id = ?" : "";
        String sql = "SELECT * FROM (" + BASE + branchSql + ") t" + where + " ORDER BY t.due_date, t.id DESC" + (limit != null ? " LIMIT " + Math.max(1, Math.min(limit, 2000)) : "");
        return branchId != null ? jdbc.query(sql, (rs, i) -> row(rs), branchId) : jdbc.query(sql, (rs, i) -> row(rs));
    }

    public Detail detail(long id, Long branchId) {
        List<Row> r = jdbc.query(BASE + " WHERE i.id = ?" + (branchId != null ? " AND i.branch_id = ?" : ""), (rs, i) -> row(rs),
            branchId != null ? new Object[] { id, branchId } : new Object[] { id });
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That invoice was not found.");
        List<Line> lines = jdbc.query("""
            SELECT l.name, l.unit, l.qty_received, l.unit_cost FROM branch_po_lines l
            WHERE l.po_id = ? AND l.qty_received > 0 ORDER BY lower(l.name)
            """, (rs, i) -> new Line(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)), r.get(0).poId());
        List<Payment> pays = jdbc.query("""
            SELECT p.id, p.amount, p.method, p.reference, p.paid_on, u.full_name FROM invoice_payments p
            JOIN users u ON u.id = p.recorded_by WHERE p.invoice_id = ? ORDER BY p.paid_on, p.id
            """, (rs, i) -> new Payment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getDate(5).toLocalDate(), rs.getString(6)), id);
        return new Detail(r.get(0), lines, pays);
    }

    public Summary summary(Long branchId) {
        List<Row> open = list(branchId, "open");
        long outstanding = open.stream().mapToLong(Row::balance).sum();
        List<Row> late = open.stream().filter(Row::overdue).toList();
        long overdue = late.stream().mapToLong(Row::balance).sum();
        List<BranchOwing> by = new ArrayList<>();
        open.stream().collect(java.util.stream.Collectors.groupingBy(Row::branchId, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()))
            .forEach((bid, rows) -> by.add(new BranchOwing(bid, rows.get(0).branch(), rows.stream().mapToLong(Row::balance).sum(),
                rows.stream().filter(Row::overdue).mapToLong(Row::balance).sum(), rows.size())));
        by.sort((a, b) -> Long.compare(b.outstanding(), a.outstanding()));
        return new Summary(outstanding, overdue, open.size(), late.size(), by);
    }

    /** HQ records money received. Serialised per invoice so two clerks cannot both pay the same balance. */
    @Transactional
    public void recordPayment(long invoiceId, long userId, long amount, String method, String reference, LocalDate paidOn) {
        if (!METHODS.contains(method)) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick how it was paid.");
        List<Long> amt = jdbc.queryForList("SELECT amount FROM invoices WHERE id = ? FOR UPDATE", Long.class, invoiceId);
        if (amt.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That invoice was not found.");
        long paid = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM invoice_payments WHERE invoice_id = ?", Long.class, invoiceId);
        long balance = amt.get(0) - paid;
        if (balance == 0) throw new ApiException(HttpStatus.CONFLICT, "This invoice is already fully paid.");
        if (amount > balance) {
            throw new ApiException(HttpStatus.CONFLICT, "Only " + new ph.coffeepostal.pos.shared.Money(balance) + " is still owed on this invoice.");
        }
        LocalDate today = LocalDate.now(MANILA);
        if (paidOn.isAfter(today)) throw new ApiException(HttpStatus.BAD_REQUEST, "The payment date cannot be in the future.");
        jdbc.update("INSERT INTO invoice_payments(invoice_id, amount, method, reference, paid_on, recorded_by) VALUES (?, ?, ?, ?, ?, ?)",
            invoiceId, amount, method, reference == null || reference.isBlank() ? null : reference.trim(), java.sql.Date.valueOf(paidOn), userId);
    }
}
