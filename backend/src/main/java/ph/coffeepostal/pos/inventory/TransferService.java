package ph.coffeepostal.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;

/** Moves stock from one branch to another: sent (stock leaves the sender), then received (stock reaches the other branch). */
@Service
public class TransferService {
    public record LineReq(Long itemId, BigDecimal qty, String unit) {}
    public record Line(long itemId, String item, String unit, long qty) {}
    public record Transfer(long id, long fromBranchId, String fromBranch, long toBranchId, String toBranch, String status, String note,
                           String createdBy, Instant createdAt, String closedBy, Instant closedAt, List<Line> lines) {}
    public record BranchOpt(long id, String name) {}

    private final JdbcTemplate jdbc;
    private final InventoryService inventory;

    public TransferService(JdbcTemplate jdbc, InventoryService inventory) { this.jdbc = jdbc; this.inventory = inventory; }

    public List<BranchOpt> otherBranches(long myBranch) {
        return jdbc.query("SELECT id, name FROM branches WHERE active AND id <> ? ORDER BY name",
            (rs, i) -> new BranchOpt(rs.getLong(1), rs.getString(2)), myBranch);
    }

    @Transactional
    public long send(long fromBranch, long toBranch, String note, List<LineReq> lines, long userId) {
        if (fromBranch == toBranch) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a different branch to send to.");
        Integer ok = jdbc.queryForObject("SELECT count(*) FROM branches WHERE id = ? AND active", Integer.class, toBranch);
        if (ok == null || ok == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a branch to send to.");
        if (lines == null || lines.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one item.");

        // one row per item, in the item's own unit
        Map<Long, Long> byItem = new LinkedHashMap<>();
        for (LineReq l : lines) {
            if (l.itemId() == null || l.qty() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Each line needs an item and a quantity.");
            if (l.qty().signum() <= 0 || l.qty().scale() > 3 || l.qty().compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a quantity like 2 or 2.5.");
            }
            if (!inventory.itemActive(l.itemId())) throw new ApiException(HttpStatus.NOT_FOUND, "An item on this transfer is not available.");
            long base = inventory.toBase(l.itemId(), l.qty().movePointRight(3).longValueExact(), l.unit());
            if (base <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "That quantity is too small to count.");
            byItem.merge(l.itemId(), base, Long::sum);
        }

        long id = jdbc.queryForObject("INSERT INTO stock_transfers(from_branch, to_branch, note, created_by) VALUES (?, ?, ?, ?) RETURNING id",
            Long.class, fromBranch, toBranch, note == null || note.isBlank() ? null : note.trim(), userId);
        for (var e : byItem.entrySet()) {
            jdbc.update("INSERT INTO stock_transfer_lines(transfer_id, item_id, qty) VALUES (?, ?, ?)", id, e.getKey(), e.getValue());
            inventory.move(fromBranch, e.getKey(), -e.getValue(), "TRANSFER_OUT", "Sent to branch " + toBranch, "TR-" + id, userId);
        }
        return id;
    }

    /** The receiving branch confirms. Only a SENT transfer can be received, and only once. */
    @Transactional
    public void receive(long id, long myBranch, long userId) {
        Transfer t = get(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That transfer no longer exists."));
        if (t.toBranchId() != myBranch) throw new ApiException(HttpStatus.NOT_FOUND, "That transfer no longer exists.");
        close(id, "RECEIVED", userId);
        for (Line l : t.lines()) {
            inventory.move(myBranch, l.itemId(), l.qty(), "TRANSFER_IN", "From " + t.fromBranch(), "TR-" + id, userId);
        }
    }

    /** The sending branch takes it back while it is still on the road; the stock returns to the sender. */
    @Transactional
    public void cancel(long id, long myBranch, long userId) {
        Transfer t = get(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That transfer no longer exists."));
        if (t.fromBranchId() != myBranch) throw new ApiException(HttpStatus.NOT_FOUND, "That transfer no longer exists.");
        close(id, "CANCELLED", userId);
        for (Line l : t.lines()) {
            inventory.move(myBranch, l.itemId(), l.qty(), "TRANSFER_IN", "Transfer cancelled", "TR-" + id, userId);
        }
    }

    private void close(long id, String status, long userId) {
        int n = jdbc.update("UPDATE stock_transfers SET status = ?, closed_by = ?, closed_at = now() WHERE id = ? AND status = 'SENT'", status, userId, id);
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "This transfer was already closed.");
    }

    /** branchId null = all branches (HQ). */
    public List<Transfer> list(Long branchId) {
        List<Transfer> heads = jdbc.query(HEAD + " WHERE (?::bigint IS NULL OR t.from_branch = ? OR t.to_branch = ?) ORDER BY t.id DESC LIMIT 200",
            this::head, branchId, branchId, branchId);
        List<Transfer> out = new ArrayList<>();
        for (Transfer t : heads) out.add(withLines(t));
        return out;
    }

    public java.util.Optional<Transfer> get(long id) {
        return jdbc.query(HEAD + " WHERE t.id = ?", this::head, id).stream().findFirst().map(this::withLines);
    }

    private static final String HEAD = """
        SELECT t.id, t.from_branch, fb.name, t.to_branch, tb.name, t.status, t.note, cu.full_name, t.created_at, xu.full_name, t.closed_at
        FROM stock_transfers t JOIN branches fb ON fb.id = t.from_branch JOIN branches tb ON tb.id = t.to_branch
        JOIN users cu ON cu.id = t.created_by LEFT JOIN users xu ON xu.id = t.closed_by
        """;

    private Transfer head(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Transfer(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getString(10), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(), List.of());
    }

    private Transfer withLines(Transfer t) {
        List<Line> lines = jdbc.query("""
            SELECT l.item_id, i.name, i.unit, l.qty FROM stock_transfer_lines l JOIN items i ON i.id = l.item_id WHERE l.transfer_id = ? ORDER BY lower(i.name)
            """, (rs, i) -> new Line(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), t.id());
        return new Transfer(t.id(), t.fromBranchId(), t.fromBranch(), t.toBranchId(), t.toBranch(), t.status(), t.note(), t.createdBy(),
            t.createdAt(), t.closedBy(), t.closedAt(), lines);
    }
}
