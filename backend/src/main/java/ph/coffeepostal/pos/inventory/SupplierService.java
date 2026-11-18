package ph.coffeepostal.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;

/** HQ purchasing: suppliers, supplier purchase orders (with partial receiving) and what HQ owes. */
@Service
public class SupplierService {
    public static final List<String> METHODS = List.of("CASH", "BANK", "GCASH", "MAYA", "CHECK");

    public record Supplier(long id, String name, String contact, String phone, String email, String address, int termsDays, boolean active, long owed) {}
    public record PoLine(long id, long itemId, String item, String unit, long qty, long unitCost, long receivedQty) {}
    public record Payment(long id, long amount, String method, String reference, LocalDate paidOn, String recordedBy) {}
    public record Po(long id, long supplierId, String supplier, long branchId, String branch, String status, String note, LocalDate expectedOn,
                     Instant createdAt, long ordered, long received, long paid, long balance, List<PoLine> lines, List<Payment> payments) {}
    public record NewLine(Long itemId, BigDecimal qty, String unit, String unitCost) {}
    public record Receive(Long lineId, BigDecimal qty) {}

    private final JdbcTemplate jdbc;
    private final InventoryService inventory;

    public SupplierService(JdbcTemplate jdbc, InventoryService inventory) { this.jdbc = jdbc; this.inventory = inventory; }

    public long userId(String username) {
        return jdbc.query("SELECT id FROM users WHERE username = ?", (rs, i) -> rs.getLong(1), username).stream().findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Your account was not found."));
    }

    // ---- suppliers ----
    public List<Supplier> suppliers() {
        return jdbc.query("""
            SELECT s.id, s.name, s.contact, s.phone, s.email, s.address, s.terms_days, s.active,
                   coalesce((SELECT sum(%s) - sum(coalesce(pay.paid, 0)) FROM supplier_pos p
                             LEFT JOIN LATERAL (SELECT sum(amount) AS paid FROM supplier_payments WHERE po_id = p.id) pay ON TRUE
                             WHERE p.supplier_id = s.id AND p.status <> 'CANCELLED'), 0) AS owed
            FROM suppliers s ORDER BY lower(s.name)
            """.formatted(RECEIVED_VALUE), (rs, i) -> new Supplier(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getBoolean(8), rs.getLong(9)));
    }

    /** Value of goods received on a PO (centavos), rounded per line. */
    private static final String RECEIVED_VALUE =
        "coalesce((SELECT sum(round(l.received_qty * l.unit_cost / 1000.0)) FROM supplier_po_lines l WHERE l.po_id = p.id), 0)";
    private static final String ORDERED_VALUE =
        "coalesce((SELECT sum(round(l.qty * l.unit_cost / 1000.0)) FROM supplier_po_lines l WHERE l.po_id = p.id), 0)";

    @Transactional
    public long addSupplier(String name, String contact, String phone, String email, String address, int termsDays) {
        if (nameTaken(name, -1)) throw new ApiException(HttpStatus.CONFLICT, "A supplier with that name already exists.", Map.of("name", "Already used."));
        return jdbc.queryForObject("INSERT INTO suppliers(name, contact, phone, email, address, terms_days) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class, name, blank(contact), blank(phone), blank(email), blank(address), termsDays);
    }

    @Transactional
    public void updateSupplier(long id, String name, String contact, String phone, String email, String address, int termsDays, Boolean active) {
        if (nameTaken(name, id)) throw new ApiException(HttpStatus.CONFLICT, "A supplier with that name already exists.", Map.of("name", "Already used."));
        int n = jdbc.update("UPDATE suppliers SET name = ?, contact = ?, phone = ?, email = ?, address = ?, terms_days = ?, active = coalesce(?, active) WHERE id = ?",
            name, blank(contact), blank(phone), blank(email), blank(address), termsDays, active, id);
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That supplier no longer exists.");
    }

    private boolean nameTaken(String name, long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE lower(name) = lower(?) AND id <> ?", Integer.class, name, exceptId);
        return n != null && n > 0;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    // ---- purchase orders ----
    @Transactional
    public long createPo(long supplierId, long branchId, String note, LocalDate expectedOn, List<NewLine> lines, long userId) {
        Integer sup = jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE id = ? AND active", Integer.class, supplierId);
        if (sup == null || sup == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a supplier.");
        Integer br = jdbc.queryForObject("SELECT count(*) FROM branches WHERE id = ? AND active", Integer.class, branchId);
        if (br == null || br == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick the branch that receives the goods.");
        if (lines == null || lines.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one item.");
        long id = jdbc.queryForObject("INSERT INTO supplier_pos(supplier_id, branch_id, note, expected_on, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class, supplierId, branchId, blank(note), expectedOn, userId);
        Map<Long, Boolean> seen = new LinkedHashMap<>();
        for (NewLine l : lines) {
            if (l.itemId() == null || l.qty() == null || l.unitCost() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Each line needs an item, a quantity and a cost.");
            if (seen.put(l.itemId(), true) != null) throw new ApiException(HttpStatus.BAD_REQUEST, "Each item can be on the order once.");
            if (l.qty().signum() <= 0 || l.qty().scale() > 3 || l.qty().compareTo(BigDecimal.valueOf(1_000_000)) > 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a quantity like 10 or 2.5.");
            if (!inventory.itemActive(l.itemId())) throw new ApiException(HttpStatus.NOT_FOUND, "An item on this order is not available.");
            long qty = inventory.toBase(l.itemId(), l.qty().movePointRight(3).longValueExact(), l.unit());
            // the cost is per the unit the quantity was typed in; store it per the item's own unit
            long cost = ph.coffeepostal.pos.shared.PesoInput.parse(l.unitCost(), true, "unitCost", "Enter a cost like 850 or 850.50.");
            if (l.unit() != null && !l.unit().isBlank()) {
                long perUnitThousandths = inventory.toBase(l.itemId(), 1000, l.unit());
                cost = BigDecimal.valueOf(cost).multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(perUnitThousandths), 0, RoundingMode.HALF_UP).longValueExact();
            }
            jdbc.update("INSERT INTO supplier_po_lines(po_id, item_id, qty, unit_cost) VALUES (?, ?, ?, ?)", id, l.itemId(), qty, cost);
        }
        return id;
    }

    /** Receives some or all of what is still due. Stock goes to the PO's branch. A line cannot be over-received. */
    @Transactional
    public void receive(long poId, List<Receive> items, long userId) {
        Object[] head = jdbc.query("SELECT branch_id, status FROM supplier_pos WHERE id = ? FOR UPDATE",
            (rs, i) -> new Object[] { rs.getLong(1), rs.getString(2) }, poId).stream().findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That order no longer exists."));
        long branch = (Long) head[0];
        String status = (String) head[1];
        if (!status.equals("ORDERED") && !status.equals("PARTIAL")) throw new ApiException(HttpStatus.CONFLICT, "This order is already " + status.toLowerCase() + ".");
        if (items == null || items.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Enter what arrived.");
        boolean any = false;
        for (Receive r : items) {
            if (r.lineId() == null || r.qty() == null || r.qty().signum() < 0 || r.qty().scale() > 3) throw new ApiException(HttpStatus.BAD_REQUEST, "Enter quantities like 10 or 2.5.");
            long q = r.qty().movePointRight(3).longValueExact();
            if (q == 0) continue;
            var line = jdbc.query("SELECT item_id, qty, received_qty FROM supplier_po_lines WHERE id = ? AND po_id = ? FOR UPDATE",
                (rs, i) -> new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) }, r.lineId(), poId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "A line is not on this order."));
            if (line[2] + q > line[1]) throw new ApiException(HttpStatus.CONFLICT, "That is more than was ordered. Still due: " + InventoryService.show(line[1] - line[2]) + ".");
            jdbc.update("UPDATE supplier_po_lines SET received_qty = received_qty + ? WHERE id = ?", q, r.lineId());
            jdbc.update("INSERT INTO supplier_po_receipts(line_id, qty, received_by) VALUES (?, ?, ?)", r.lineId(), q, userId);
            inventory.move(branch, line[0], q, "PO_RECEIVE", "From supplier", "SPO-" + poId, userId);
            any = true;
        }
        if (!any) throw new ApiException(HttpStatus.BAD_REQUEST, "Enter what arrived.");
        Integer open = jdbc.queryForObject("SELECT count(*) FROM supplier_po_lines WHERE po_id = ? AND received_qty < qty", Integer.class, poId);
        jdbc.update("UPDATE supplier_pos SET status = ? WHERE id = ?", open != null && open == 0 ? "RECEIVED" : "PARTIAL", poId);
    }

    /** The supplier will not send the rest: close the order at what arrived. */
    @Transactional
    public void closeShort(long poId) {
        int n = jdbc.update("UPDATE supplier_pos SET status = 'RECEIVED' WHERE id = ? AND status = 'PARTIAL'", poId);
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "Only an order that is partly received can be closed short.");
    }

    @Transactional
    public void cancel(long poId) {
        int n = jdbc.update("""
            UPDATE supplier_pos SET status = 'CANCELLED' WHERE id = ? AND status = 'ORDERED'
              AND NOT EXISTS (SELECT 1 FROM supplier_payments WHERE po_id = ?)
            """, poId, poId);
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "Only an order with nothing received and nothing paid can be cancelled.");
    }

    /** Pay the supplier. Cannot pay more than the order's value (ordered, so a deposit is fine). */
    @Transactional
    public void pay(long poId, long amount, String method, String reference, LocalDate paidOn, long userId) {
        if (!METHODS.contains(method)) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick how it was paid.");
        long[] t = jdbc.query("SELECT %s, coalesce((SELECT sum(amount) FROM supplier_payments WHERE po_id = p.id), 0), 0 FROM supplier_pos p WHERE p.id = ? AND p.status <> 'CANCELLED' FOR UPDATE OF p"
            .formatted(ORDERED_VALUE), (rs, i) -> new long[] { rs.getLong(1), rs.getLong(2) }, poId).stream().findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That order is not open."));
        if (t[1] + amount > t[0]) throw new ApiException(HttpStatus.CONFLICT, "That is more than the order's total. Left to pay: PHP " + BigDecimal.valueOf(t[0] - t[1], 2).toPlainString() + ".");
        jdbc.update("INSERT INTO supplier_payments(po_id, amount, method, reference, paid_on, recorded_by) VALUES (?, ?, ?, ?, ?, ?)",
            poId, amount, method, blank(reference), paidOn, userId);
    }

    public List<Po> list(Long supplierId) {
        return jdbc.query(HEAD + " WHERE (?::bigint IS NULL OR p.supplier_id = ?) ORDER BY p.id DESC LIMIT 200", (rs, i) -> head(rs), supplierId, supplierId)
            .stream().map(this::full).toList();
    }

    public Optional<Po> get(long id) {
        return jdbc.query(HEAD + " WHERE p.id = ?", (rs, i) -> head(rs), id).stream().findFirst().map(this::full);
    }

    private final String HEAD = """
        SELECT p.id, p.supplier_id, s.name, p.branch_id, b.name, p.status, p.note, p.expected_on, p.created_at,
               %s AS ordered, %s AS received, coalesce((SELECT sum(amount) FROM supplier_payments WHERE po_id = p.id), 0) AS paid
        FROM supplier_pos p JOIN suppliers s ON s.id = p.supplier_id JOIN branches b ON b.id = p.branch_id
        """.formatted(ORDERED_VALUE, RECEIVED_VALUE);

    private Po head(java.sql.ResultSet rs) throws java.sql.SQLException {
        long received = rs.getLong(11), paid = rs.getLong(12);
        return new Po(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getDate(8) == null ? null : rs.getDate(8).toLocalDate(), rs.getTimestamp(9).toInstant(), rs.getLong(10), received, paid, received - paid, List.of(), List.of());
    }

    private Po full(Po p) {
        List<PoLine> lines = jdbc.query("""
            SELECT l.id, l.item_id, i.name, i.unit, l.qty, l.unit_cost, l.received_qty FROM supplier_po_lines l JOIN items i ON i.id = l.item_id
            WHERE l.po_id = ? ORDER BY l.id
            """, (rs, i) -> new PoLine(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)), p.id());
        List<Payment> pays = jdbc.query("""
            SELECT y.id, y.amount, y.method, y.reference, y.paid_on, u.full_name FROM supplier_payments y JOIN users u ON u.id = y.recorded_by
            WHERE y.po_id = ? ORDER BY y.paid_on, y.id
            """, (rs, i) -> new Payment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getDate(5).toLocalDate(), rs.getString(6)), p.id());
        return new Po(p.id(), p.supplierId(), p.supplier(), p.branchId(), p.branch(), p.status(), p.note(), p.expectedOn(), p.createdAt(),
            p.ordered(), p.received(), p.paid(), p.balance(), lines, pays);
    }
}
