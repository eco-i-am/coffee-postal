package ph.coffeepostal.pos.finance;

import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * The cashier's cash drawer. A shift starts with an opening float, collects cash in/out,
 * and ends with a count that is compared to what the system expects. Money is centavos.
 * Expected = opening + cash sales (paid orders, not refunded) + cash in - cash out.
 */
@Service
public class ShiftService {
    public record Movement(long id, String kind, long amount, String reason, Instant at, String by) {}
    public record Summary(long id, Instant openedAt, long openingCash, int orders, long sales, long cashSales,
                          long cashIn, long cashOut, long expected, List<Movement> movements) {}
    public record Closed(long id, int zNo, Instant openedAt, Instant closedAt, long expected, long counted, long variance) {}
    public record HistoryRow(long id, String cashier, Instant openedAt, Instant closedAt, long openingCash,
                             Long expected, Long counted, Long variance, Integer zNo, boolean photo) {}

    private final JdbcTemplate jdbc;

    public ShiftService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** The signed-in cashier's open shift, or null. */
    public Summary current(long userId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM shifts WHERE cashier_id = ? AND closed_at IS NULL", Long.class, userId);
        return ids.isEmpty() ? null : summary(ids.get(0));
    }

    Summary summary(long shiftId) {
        var s = jdbc.queryForMap("SELECT opened_at, opening_cash FROM shifts WHERE id = ?", shiftId);
        var o = jdbc.queryForMap("""
            SELECT count(*) AS n, coalesce(sum(total), 0) AS sales FROM orders WHERE shift_id = ? AND status = 'PAID'
            """, shiftId);
        long cashSales = jdbc.queryForObject("""
            SELECT coalesce(sum(p.amount), 0) FROM order_payments p JOIN orders o ON o.id = p.order_id
            WHERE o.shift_id = ? AND o.status = 'PAID' AND p.method = 'CASH'
            """, Long.class, shiftId);
        long in = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM cash_movements WHERE shift_id = ? AND kind = 'IN'", Long.class, shiftId);
        long out = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM cash_movements WHERE shift_id = ? AND kind = 'OUT'", Long.class, shiftId);
        long opening = ((Number) s.get("opening_cash")).longValue();
        List<Movement> moves = jdbc.query("""
            SELECT m.id, m.kind, m.amount, m.reason, m.created_at, u.full_name FROM cash_movements m JOIN users u ON u.id = m.user_id
            WHERE m.shift_id = ? ORDER BY m.created_at DESC, m.id DESC
            """, (rs, i) -> new Movement(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getString(6)), shiftId);
        return new Summary(shiftId, ((java.sql.Timestamp) s.get("opened_at")).toInstant(), opening, ((Number) o.get("n")).intValue(),
            ((Number) o.get("sales")).longValue(), cashSales, in, out, opening + cashSales + in - out, moves);
    }

    /** Keeps the picture taken when the shift was opened. */
    public void savePhoto(long shiftId, byte[] jpeg) {
        jdbc.update("INSERT INTO shift_photos(shift_id, jpeg) VALUES (?, ?) ON CONFLICT (shift_id) DO NOTHING", shiftId, jpeg);
    }

    /** The opening picture, only if the shift belongs to the branch. Null if there is none. */
    public byte[] photo(long shiftId, long branchId) {
        List<byte[]> r = jdbc.query("SELECT p.jpeg FROM shift_photos p JOIN shifts s ON s.id = p.shift_id WHERE p.shift_id = ? AND s.branch_id = ?",
            (rs, i) -> rs.getBytes(1), shiftId, branchId);
        return r.isEmpty() ? null : r.get(0);
    }

    public Summary open(long branchId, long userId, long openingCash) {
        try {
            long id = jdbc.queryForObject("INSERT INTO shifts(branch_id, cashier_id, opening_cash) VALUES (?, ?, ?) RETURNING id",
                Long.class, branchId, userId, openingCash);
            return summary(id);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "You already have a shift open.");
        }
    }

    @Transactional
    public Summary addMovement(long userId, String kind, long amount, String reason) {
        Summary cur = current(userId);
        if (cur == null) throw new ApiException(HttpStatus.CONFLICT, "Open a shift first.");
        if (kind.equals("OUT") && amount > cur.expected()) {
            throw new ApiException(HttpStatus.CONFLICT, "The drawer only has what the system expects, so you cannot take out more than that.");
        }
        jdbc.update("INSERT INTO cash_movements(shift_id, kind, amount, reason, user_id) VALUES (?, ?, ?, ?, ?)",
            cur.id(), kind, amount, reason.trim(), userId);
        return summary(cur.id());
    }

    @Transactional
    public Closed close(long userId, long counted) {
        Summary cur = current(userId);
        if (cur == null) throw new ApiException(HttpStatus.CONFLICT, "You do not have an open shift.");
        // the Z number is a running count per branch; locking the branch row keeps two closes from taking the same one
        long branchId = jdbc.queryForObject("SELECT branch_id FROM shifts WHERE id = ?", Long.class, cur.id());
        jdbc.queryForObject("SELECT id FROM branches WHERE id = ? FOR UPDATE", Long.class, branchId);
        int zNo = jdbc.queryForObject("SELECT coalesce(max(z_no), 0) + 1 FROM shifts WHERE branch_id = ?", Integer.class, branchId);
        int n = jdbc.update("UPDATE shifts SET closed_at = now(), expected_cash = ?, counted_cash = ?, z_no = ? WHERE id = ? AND closed_at IS NULL",
            cur.expected(), counted, zNo, cur.id());
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "This shift was already closed.");
        Instant closedAt = jdbc.queryForObject("SELECT closed_at FROM shifts WHERE id = ?", java.sql.Timestamp.class, cur.id()).toInstant();
        return new Closed(cur.id(), zNo, cur.openedAt(), closedAt, cur.expected(), counted, counted - cur.expected());
    }

    /** Who ran this shift, or null if there is no such shift. */
    public Long cashierOf(long shiftId) {
        List<Long> r = jdbc.queryForList("SELECT cashier_id FROM shifts WHERE id = ?", Long.class, shiftId);
        return r.isEmpty() ? null : r.get(0);
    }

    public List<HistoryRow> history(long branchId) {
        return jdbc.query("""
            SELECT s.id, u.full_name, s.opened_at, s.closed_at, s.opening_cash, s.expected_cash, s.counted_cash, s.z_no,
                   EXISTS (SELECT 1 FROM shift_photos p WHERE p.shift_id = s.id)
            FROM shifts s JOIN users u ON u.id = s.cashier_id WHERE s.branch_id = ?
            ORDER BY s.opened_at DESC LIMIT 40
            """, (rs, i) -> {
                java.sql.Timestamp closed = rs.getTimestamp(4);
                long exp = rs.getLong(6); boolean hasExp = !rs.wasNull();
                long cnt = rs.getLong(7); boolean hasCnt = !rs.wasNull();
                return new HistoryRow(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                    closed == null ? null : closed.toInstant(), rs.getLong(5),
                    hasExp ? exp : null, hasCnt ? cnt : null, hasExp && hasCnt ? cnt - exp : null, (Integer) rs.getObject(8), rs.getBoolean(9));
            }, branchId);
    }
}
