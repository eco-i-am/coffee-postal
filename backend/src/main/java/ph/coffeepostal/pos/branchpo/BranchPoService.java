package ph.coffeepostal.pos.branchpo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.inventory.InventoryService;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * Branch -> HQ purchase orders: REQUESTED -> APPROVED -> SHIPPED -> RECEIVED (or REJECTED / CANCELLED).
 * Every status change is a guarded UPDATE (... WHERE status = expected), so two people acting at once cannot both win.
 */
@Service
public class BranchPoService {
    public record Row(long id, long branchId, String branch, String status, Instant requestedAt, int lines, long value) {}
    public record Line(long id, long itemId, String name, String unit, long unitCost, long qtyOrdered, Long qtyApproved, Long qtyReceived) {}
    public record Detail(Row po, String note, String rejectReason, String requestedBy, Instant decidedAt, Instant shippedAt,
                         Instant receivedAt, List<Line> lines) {}
    public record NewLine(long itemId, long qty) {}

    private final JdbcTemplate jdbc;
    private final InventoryService inventory;

    private final int termsDays;

    public BranchPoService(JdbcTemplate jdbc, InventoryService inventory,
                           @org.springframework.beans.factory.annotation.Value("${app.invoice-terms-days:15}") int termsDays) {
        this.jdbc = jdbc; this.inventory = inventory; this.termsDays = termsDays;
    }

    private static final String ROW = """
        SELECT p.id, p.branch_id, b.name AS branch, p.status, p.requested_at,
               (SELECT count(*) FROM branch_po_lines l WHERE l.po_id = p.id) AS n,
               (SELECT coalesce(sum(coalesce(l.qty_received, l.qty_approved, l.qty_ordered) * l.unit_cost), 0)
                  FROM branch_po_lines l WHERE l.po_id = p.id) AS value
        FROM branch_pos p JOIN branches b ON b.id = p.branch_id
        """;

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getLong("branch_id"), rs.getString("branch"), rs.getString("status"),
            rs.getTimestamp("requested_at").toInstant(), rs.getInt("n"), rs.getLong("value"));
    }

    public List<Row> list(Long branchId, String status, int limit) {
        StringBuilder sql = new StringBuilder(ROW + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (branchId != null) { sql.append(" AND p.branch_id = ?"); args.add(branchId); }
        if (status != null && !status.isBlank()) {
            if (!List.of("REQUESTED", "APPROVED", "REJECTED", "SHIPPED", "RECEIVED", "CANCELLED").contains(status)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown status.");
            }
            sql.append(" AND p.status = ?"); args.add(status);
        }
        sql.append(" ORDER BY p.requested_at DESC, p.id DESC LIMIT " + Math.max(1, Math.min(limit, 2000)));
        return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
    }

    /** branchId non-null = a branch user, who may only see their own orders. */
    public Detail detail(long id, Long branchId) {
        List<Row> r = jdbc.query(ROW + " WHERE p.id = ?" + (branchId != null ? " AND p.branch_id = ?" : ""),
            (rs, i) -> row(rs), branchId != null ? new Object[] { id, branchId } : new Object[] { id });
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That purchase order was not found.");
        Map<String, Object> p = jdbc.queryForMap("""
            SELECT p.note, p.reject_reason, u.full_name AS by_name, p.decided_at, p.shipped_at, p.received_at
            FROM branch_pos p JOIN users u ON u.id = p.requested_by WHERE p.id = ?
            """, id);
        List<Line> lines = jdbc.query("""
            SELECT id, item_id, name, unit, unit_cost, qty_ordered, qty_approved, qty_received
            FROM branch_po_lines WHERE po_id = ? ORDER BY lower(name)
            """, (rs, i) -> new Line(rs.getLong("id"), rs.getLong("item_id"), rs.getString("name"), rs.getString("unit"),
                rs.getLong("unit_cost"), rs.getLong("qty_ordered"), nullableLong(rs, "qty_approved"), nullableLong(rs, "qty_received")), id);
        return new Detail(r.get(0), (String) p.get("note"), (String) p.get("reject_reason"), (String) p.get("by_name"),
            instant(p.get("decided_at")), instant(p.get("shipped_at")), instant(p.get("received_at")), lines);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(Object ts) { return ts == null ? null : ((java.sql.Timestamp) ts).toInstant(); }

    @Transactional
    public long create(long branchId, long userId, String note, List<NewLine> lines) {
        Map<Long, Long> merged = new LinkedHashMap<>();
        for (NewLine l : lines) {
            if (l.qty() <= 0 || l.qty() > 100_000) throw new ApiException(HttpStatus.BAD_REQUEST, "Quantities must be between 1 and 100,000.");
            merged.merge(l.itemId(), l.qty(), Long::sum);
        }
        if (merged.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one item.");
        long id = jdbc.queryForObject("INSERT INTO branch_pos(branch_id, note, requested_by) VALUES (?, ?, ?) RETURNING id",
            Long.class, branchId, blank(note), userId);
        for (var e : merged.entrySet()) {
            int n = jdbc.update("""
                INSERT INTO branch_po_lines(po_id, item_id, name, unit, unit_cost, qty_ordered)
                SELECT ?, i.id, i.name, i.unit, i.cost, ? FROM items i WHERE i.id = ? AND i.active
                """, id, e.getValue(), e.getKey());
            if (n == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "An item on this order is no longer available. Refresh and try again.");
        }
        return id;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** Branch cancels its own order while HQ has not looked at it. */
    @Transactional
    public void cancel(long id, long branchId) {
        int n = jdbc.update("UPDATE branch_pos SET status = 'CANCELLED' WHERE id = ? AND branch_id = ? AND status = 'REQUESTED'", id, branchId);
        if (n == 0) throw stale(id, branchId, "Only a request HQ has not reviewed yet can be cancelled.");
    }

    /** approved: lineId -> approved quantity (0..ordered). Lines not listed are approved in full. */
    @Transactional
    public void approve(long id, long adminId, Map<Long, Long> approved) {
        List<Line> lines = jdbc.query("SELECT id, qty_ordered FROM branch_po_lines WHERE po_id = ?",
            (rs, i) -> new Line(rs.getLong("id"), 0, null, null, 0, rs.getLong("qty_ordered"), null, null), id);
        if (lines.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That purchase order was not found.");
        long total = 0;
        for (Line l : lines) {
            long q = approved.getOrDefault(l.id(), l.qtyOrdered());
            if (q < 0 || q > l.qtyOrdered()) throw new ApiException(HttpStatus.BAD_REQUEST, "You can approve between 0 and the quantity ordered.");
            total += q;
        }
        if (total == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Approve at least one item, or reject the order instead.");
        int n = jdbc.update("UPDATE branch_pos SET status = 'APPROVED', decided_by = ?, decided_at = now() WHERE id = ? AND status = 'REQUESTED'", adminId, id);
        if (n == 0) throw stale(id, null, "This order was already handled.");
        for (Line l : lines) {
            jdbc.update("UPDATE branch_po_lines SET qty_approved = ? WHERE id = ?", approved.getOrDefault(l.id(), l.qtyOrdered()), l.id());
        }
    }

    @Transactional
    public void reject(long id, long adminId, String reason) {
        int n = jdbc.update("UPDATE branch_pos SET status = 'REJECTED', decided_by = ?, decided_at = now(), reject_reason = ? WHERE id = ? AND status = 'REQUESTED'",
            adminId, reason.trim(), id);
        if (n == 0) throw stale(id, null, "This order was already handled.");
    }

    @Transactional
    public void ship(long id) {
        int n = jdbc.update("UPDATE branch_pos SET status = 'SHIPPED', shipped_at = now() WHERE id = ? AND status = 'APPROVED'", id);
        if (n == 0) throw stale(id, null, "Only an approved order can be marked as sent.");
    }

    /** Branch counts what arrived. Adds it to stock. received: lineId -> quantity (0..approved). */
    @Transactional
    public void receive(long id, long branchId, long userId, Map<Long, Long> received) {
        int n = jdbc.update("UPDATE branch_pos SET status = 'RECEIVED', received_by = ?, received_at = now() WHERE id = ? AND branch_id = ? AND status = 'SHIPPED'",
            userId, id, branchId);
        if (n == 0) throw stale(id, branchId, "Only an order that HQ has sent can be received.");
        List<Line> lines = jdbc.query("SELECT id, item_id, qty_approved FROM branch_po_lines WHERE po_id = ?",
            (rs, i) -> new Line(rs.getLong("id"), rs.getLong("item_id"), null, null, 0, 0, rs.getLong("qty_approved"), null), id);
        for (Line l : lines) {
            long q = received.getOrDefault(l.id(), l.qtyApproved());
            if (q < 0 || q > l.qtyApproved()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot receive more than HQ approved.");   // rolls the whole receive back
            }
            jdbc.update("UPDATE branch_po_lines SET qty_received = ? WHERE id = ?", q, l.id());
            if (q > 0) inventory.move(branchId, l.itemId(), q * InventoryService.SCALE, "PO_RECEIVE", null, "PO-" + id, userId);
        }
        // Bill the branch for what actually arrived (nothing arrived = nothing to pay).
        jdbc.update("""
            INSERT INTO invoices(po_id, branch_id, amount, due_date)
            SELECT p.id, p.branch_id, sum(l.qty_received * l.unit_cost), (now() AT TIME ZONE 'Asia/Manila')::date + ?
            FROM branch_pos p JOIN branch_po_lines l ON l.po_id = p.id
            WHERE p.id = ? GROUP BY p.id, p.branch_id HAVING sum(l.qty_received * l.unit_cost) > 0
            """, termsDays, id);
    }

    private ApiException stale(long id, Long branchId, String msg) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM branch_pos WHERE id = ?" + (branchId != null ? " AND branch_id = " + branchId : ""),
            Integer.class, id);
        return exists != null && exists > 0 ? new ApiException(HttpStatus.CONFLICT, msg)
                                            : new ApiException(HttpStatus.NOT_FOUND, "That purchase order was not found.");
    }
}
