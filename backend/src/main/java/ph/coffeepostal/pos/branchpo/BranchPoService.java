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
 * Branch -> HQ purchase orders, nine stages:
 * REQUESTED -> PENDING -> TO_PAY -> PROCESSING -> FOR_PICKUP -> IN_TRANSIT -> PICKED_UP -> DELIVERED -> COMPLETED
 * (or REJECTED by HQ, CANCELLED by the branch). Every change is a guarded UPDATE (... WHERE status = expected), so two people
 * acting at once cannot both win, and every change is written to branch_po_status_history.
 */
@Service
public class BranchPoService {
    public record Row(long id, long branchId, String branch, String status, Instant requestedAt, int lines, long value, java.time.LocalDate deliverOn) {}
    public record Rules(int leadTimeHours, java.time.LocalDate earliest) {}
    public record Line(long id, long itemId, String name, String unit, long unitCost, long qtyOrdered, Long qtyApproved, Long qtyReceived) {}
    public record Step(String from, String to, String by, String note, Instant at) {}
    public record Detail(Row po, String note, String rejectReason, String requestedBy, Instant decidedAt, Instant shippedAt,
                         Instant receivedAt, List<Line> lines, List<Step> history) {}

    public static final List<String> STATUSES = List.of("REQUESTED", "PENDING", "TO_PAY", "PROCESSING", "FOR_PICKUP", "IN_TRANSIT",
        "PICKED_UP", "DELIVERED", "COMPLETED", "REJECTED", "CANCELLED");
    /** The moves HQ can make with advance(). Approve, reject, cancel and receive are their own actions. */
    public static final Map<String, List<String>> NEXT = Map.of(
        "PENDING", List.of("TO_PAY", "PROCESSING"),
        "TO_PAY", List.of("PROCESSING"),
        "PROCESSING", List.of("FOR_PICKUP"),
        "FOR_PICKUP", List.of("IN_TRANSIT", "PICKED_UP"),
        "IN_TRANSIT", List.of("PICKED_UP", "DELIVERED"),
        "PICKED_UP", List.of("DELIVERED"));
    /** Where the branch can already count what arrived (it may be in hand before HQ updates the status). */
    static final List<String> RECEIVABLE = List.of("FOR_PICKUP", "IN_TRANSIT", "PICKED_UP", "DELIVERED");
    private static final List<String> REJECTABLE = List.of("REQUESTED", "PENDING", "TO_PAY");
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
               p.deliver_on,
               (SELECT count(*) FROM branch_po_lines l WHERE l.po_id = p.id) AS n,
               (SELECT coalesce(sum(coalesce(l.qty_received, l.qty_approved, l.qty_ordered) * l.unit_cost), 0)
                  FROM branch_po_lines l WHERE l.po_id = p.id) AS value
        FROM branch_pos p JOIN branches b ON b.id = p.branch_id
        """;

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getLong("branch_id"), rs.getString("branch"), rs.getString("status"),
            rs.getTimestamp("requested_at").toInstant(), rs.getInt("n"), rs.getLong("value"),
            rs.getDate("deliver_on") == null ? null : rs.getDate("deliver_on").toLocalDate());
    }

    public List<Row> list(Long branchId, String status, int limit) {
        StringBuilder sql = new StringBuilder(ROW + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (branchId != null) { sql.append(" AND p.branch_id = ?"); args.add(branchId); }
        if (status != null && !status.isBlank()) {
            if (!STATUSES.contains(status)) {
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
        List<Step> history = jdbc.query("""
            SELECT h.from_status, h.to_status, coalesce(u.full_name, ''), h.note, h.at FROM branch_po_status_history h
            LEFT JOIN users u ON u.id = h.by_user WHERE h.po_id = ? ORDER BY h.id
            """, (rs, i) -> new Step(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), id);
        return new Detail(r.get(0), (String) p.get("note"), (String) p.get("reject_reason"), (String) p.get("by_name"),
            instant(p.get("decided_at")), instant(p.get("shipped_at")), instant(p.get("received_at")), lines, history);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(Object ts) { return ts == null ? null : ((java.sql.Timestamp) ts).toInstant(); }

    @Transactional
    /** How many hours ahead of delivery an order must be sent. HQ sets it in Settings; 48 until then. */
    public int leadTimeHours() {
        List<String> v = jdbc.queryForList("SELECT value FROM settings WHERE key = 'po_lead_time_hours'", String.class);
        try { return v.isEmpty() ? 48 : Integer.parseInt(v.get(0).trim()); } catch (NumberFormatException e) { return 48; }
    }

    /** The earliest delivery day allowed for an order sent now. */
    public Rules rules() {
        int h = leadTimeHours();
        return new Rules(h, java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Manila")).plusHours(h).toLocalDate());
    }

    public long create(long branchId, long userId, String note, List<NewLine> lines) { return create(branchId, userId, note, lines, null); }

    /** deliverOn null = the earliest allowed day. An earlier day than the lead time allows is refused. */
    public long create(long branchId, long userId, String note, List<NewLine> lines, java.time.LocalDate deliverOn) {
        Rules rules = rules();
        java.time.LocalDate on = deliverOn == null ? rules.earliest() : deliverOn;
        if (on.isBefore(rules.earliest())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Delivery must be at least " + rules.leadTimeHours() + " hours after you send the order. The earliest day is " + rules.earliest() + ".",
                Map.of("deliverOn", "Pick " + rules.earliest() + " or later."));
        }
        Map<Long, Long> merged = new LinkedHashMap<>();
        for (NewLine l : lines) {
            if (l.qty() <= 0 || l.qty() > 100_000) throw new ApiException(HttpStatus.BAD_REQUEST, "Quantities must be between 1 and 100,000.");
            merged.merge(l.itemId(), l.qty(), Long::sum);
        }
        if (merged.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one item.");
        long id = jdbc.queryForObject("INSERT INTO branch_pos(branch_id, note, requested_by, deliver_on) VALUES (?, ?, ?, ?) RETURNING id",
            Long.class, branchId, blank(note), userId, on);
        for (var e : merged.entrySet()) {
            int n = jdbc.update("""
                INSERT INTO branch_po_lines(po_id, item_id, name, unit, unit_cost, qty_ordered)
                SELECT ?, i.id, i.name, i.unit, i.cost, ? FROM items i WHERE i.id = ? AND i.active AND i.orderable
                """, id, e.getValue(), e.getKey());
            if (n == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "An item on this order is no longer available. Refresh and try again.");
        }
        log(id, null, "REQUESTED", userId, null);
        return id;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private void log(long id, String from, String to, Long userId, String note) {
        jdbc.update("INSERT INTO branch_po_status_history(po_id, from_status, to_status, by_user, note) VALUES (?, ?, ?, ?, ?)", id, from, to, userId, blank(note));
    }

    /** Branch cancels its own order while HQ has not looked at it. */
    @Transactional
    public void cancel(long id, long branchId, long userId) {
        int n = jdbc.update("UPDATE branch_pos SET status = 'CANCELLED' WHERE id = ? AND branch_id = ? AND status = 'REQUESTED'", id, branchId);
        if (n == 0) throw stale(id, branchId, "Only a request HQ has not reviewed yet can be cancelled.");
        log(id, "REQUESTED", "CANCELLED", userId, null);
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
        int n = jdbc.update("UPDATE branch_pos SET status = 'PENDING', decided_by = ?, decided_at = now() WHERE id = ? AND status = 'REQUESTED'", adminId, id);
        if (n == 0) throw stale(id, null, "This order was already handled.");
        log(id, "REQUESTED", "PENDING", adminId, null);
        for (Line l : lines) {
            jdbc.update("UPDATE branch_po_lines SET qty_approved = ? WHERE id = ?", approved.getOrDefault(l.id(), l.qtyOrdered()), l.id());
        }
    }

    @Transactional
    public void reject(long id, long adminId, String reason) {
        String from = jdbc.query("SELECT status FROM branch_pos WHERE id = ?", (rs, i) -> rs.getString(1), id).stream().findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That purchase order was not found."));
        if (!REJECTABLE.contains(from)) throw new ApiException(HttpStatus.CONFLICT, "This order can no longer be rejected.");
        int n = jdbc.update("UPDATE branch_pos SET status = 'REJECTED', decided_by = ?, decided_at = now(), reject_reason = ? WHERE id = ? AND status = ?",
            adminId, reason.trim(), id, from);
        if (n == 0) throw stale(id, null, "This order was already handled.");
        log(id, from, "REJECTED", adminId, reason);
    }

    /** HQ moves the order one step along (see NEXT). The step must be allowed from where the order is now. */
    @Transactional
    public void advance(long id, String to, long adminId, String note) {
        String from = jdbc.query("SELECT status FROM branch_pos WHERE id = ?", (rs, i) -> rs.getString(1), id).stream().findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That purchase order was not found."));
        if (!NEXT.getOrDefault(from, List.of()).contains(to)) {
            throw new ApiException(HttpStatus.CONFLICT, "An order that is " + from.toLowerCase().replace('_', ' ') + " cannot move to " + String.valueOf(to).toLowerCase().replace('_', ' ') + ".");
        }
        int n = jdbc.update("UPDATE branch_pos SET status = ?, shipped_at = CASE WHEN ? = 'IN_TRANSIT' THEN now() ELSE shipped_at END WHERE id = ? AND status = ?", to, to, id, from);
        if (n == 0) throw stale(id, null, "This order was just changed by someone else. Refresh and try again.");
        log(id, from, to, adminId, note);
    }

    /**
     * Branch counts a delivery and adds it to stock. received: lineId -> quantity in THIS delivery (0..still due).
     * Lines not listed are taken as the full amount still due. The order is COMPLETED once everything approved has arrived,
     * otherwise it stays DELIVERED so the rest can come in a later delivery. The invoice follows what has really arrived.
     */
    @Transactional
    public void receive(long id, long branchId, long userId, Map<Long, Long> received) {
        String from = jdbc.query("SELECT status FROM branch_pos WHERE id = ? AND branch_id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id, branchId)
            .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That purchase order was not found."));
        if (!RECEIVABLE.contains(from)) throw new ApiException(HttpStatus.CONFLICT, "Only an order that HQ has prepared for you can be received.");
        List<Line> lines = jdbc.query("SELECT id, item_id, qty_approved, qty_received FROM branch_po_lines WHERE po_id = ? ORDER BY id FOR UPDATE",
            (rs, i) -> new Line(rs.getLong("id"), rs.getLong("item_id"), null, null, 0, 0, rs.getLong("qty_approved"), rs.getLong("qty_received")), id);
        long total = 0;
        for (Line l : lines) {
            long due = l.qtyApproved() - l.qtyReceived();
            long q = received.getOrDefault(l.id(), due);
            if (q < 0 || q > due) throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot receive more than HQ approved. Still due: " + due + ".");
            if (q == 0) continue;
            total += q;
            jdbc.update("UPDATE branch_po_lines SET qty_received = coalesce(qty_received, 0) + ? WHERE id = ?", q, l.id());
            jdbc.update("INSERT INTO branch_po_receipts(line_id, qty, received_by) VALUES (?, ?, ?)", l.id(), q, userId);
            inventory.move(branchId, l.itemId(), q * InventoryService.SCALE, "PO_RECEIVE", null, "PO-" + id, userId);
        }
        if (total == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Enter what arrived.");
        Integer open = jdbc.queryForObject("SELECT count(*) FROM branch_po_lines WHERE po_id = ? AND coalesce(qty_received, 0) < qty_approved", Integer.class, id);
        String to = open != null && open == 0 ? "COMPLETED" : "DELIVERED";
        jdbc.update("UPDATE branch_pos SET status = ?, received_by = ?, received_at = now() WHERE id = ?", to, userId, id);
        if (!from.equals(to)) log(id, from, to, userId, to.equals("COMPLETED") ? "Everything arrived" : "Partly received");
        else log(id, from, to, userId, "Partly received");
        billReceived(id);
    }

    /** The branch will not get the rest (or anything): complete the order at what arrived. Nothing arrived = nothing to pay. */
    @Transactional
    public void closeShort(long id, long branchId, long userId) {
        String from = jdbc.query("SELECT status FROM branch_pos WHERE id = ? AND branch_id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id, branchId)
            .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That purchase order was not found."));
        if (!RECEIVABLE.contains(from)) throw new ApiException(HttpStatus.CONFLICT, "Only an order that HQ has prepared for you can be completed.");
        jdbc.update("UPDATE branch_pos SET status = 'COMPLETED', received_by = ?, received_at = now() WHERE id = ?", userId, id);
        log(id, from, "COMPLETED", userId, "Completed with what arrived");
    }

    /** Bill the branch for what has actually arrived: one invoice per order, kept in step with each delivery. */
    private void billReceived(long id) {
        Long amount = jdbc.queryForObject("SELECT coalesce(sum(coalesce(qty_received, 0) * unit_cost), 0) FROM branch_po_lines WHERE po_id = ?", Long.class, id);
        if (amount == null || amount <= 0) return;
        int n = jdbc.update("UPDATE invoices SET amount = ? WHERE po_id = ?", amount, id);
        if (n == 0) {
            jdbc.update("""
                INSERT INTO invoices(po_id, branch_id, amount, due_date)
                SELECT p.id, p.branch_id, ?, (now() AT TIME ZONE 'Asia/Manila')::date + ? FROM branch_pos p WHERE p.id = ?
                """, amount, termsDays, id);
        }
    }

    private ApiException stale(long id, Long branchId, String msg) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM branch_pos WHERE id = ?" + (branchId != null ? " AND branch_id = " + branchId : ""),
            Integer.class, id);
        return exists != null && exists > 0 ? new ApiException(HttpStatus.CONFLICT, msg)
                                            : new ApiException(HttpStatus.NOT_FOUND, "That purchase order was not found.");
    }
}
