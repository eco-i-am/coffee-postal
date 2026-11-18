package ph.coffeepostal.pos.finance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * X and Z readings for one shift. The X reading is the report on a shift that is still open (nothing changes);
 * the Z reading is the same report on a closed shift, with its running Z number and the drawer count.
 * Gross is before discounts; net is what customers paid. Refunded orders are shown apart and are not in net.
 * Money is centavos.
 */
@Service
public class ReadingService {
    public record Line(String label, int count, long amount) {}
    public record Reading(String kind, Integer zNo, long shiftId, long branchId, String branch, boolean vatRegistered, String cashier,
                          Instant openedAt, Instant closedAt, int orders, Long firstOrderNo, Long lastOrderNo,
                          long gross, long discounts, long net, long vatable, long vat,
                          int refundCount, long refunded,
                          List<Line> payments, List<Line> discountLines,
                          long openingCash, long cashSales, long cashIn, long cashOut,
                          long expected, Long counted, Long variance) {}

    private final JdbcTemplate jdbc;

    public ReadingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Reading reading(long shiftId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT s.branch_id, b.name AS branch, b.vat_registered, u.full_name, s.opened_at, s.closed_at,
                   s.opening_cash, s.expected_cash, s.counted_cash, s.z_no
            FROM shifts s JOIN branches b ON b.id = s.branch_id JOIN users u ON u.id = s.cashier_id WHERE s.id = ?
            """, shiftId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That shift was not found.");
        Map<String, Object> s = rows.get(0);

        Map<String, Object> o = jdbc.queryForMap("""
            SELECT count(*) AS n, min(order_no) AS first_no, max(order_no) AS last_no,
                   coalesce(sum(subtotal), 0) AS gross, coalesce(sum(discount), 0) AS disc, coalesce(sum(total), 0) AS net,
                   coalesce(sum(vatable), 0) AS vatable, coalesce(sum(vat), 0) AS vat
            FROM orders WHERE shift_id = ? AND status = 'PAID'
            """, shiftId);
        Map<String, Object> rf = jdbc.queryForMap("SELECT count(*) AS n, coalesce(sum(total), 0) AS amount FROM orders WHERE shift_id = ? AND status = 'REFUNDED'", shiftId);
        List<Line> pays = jdbc.query("""
            SELECT p.method, count(*), sum(p.amount) FROM order_payments p JOIN orders o ON o.id = p.order_id
            WHERE o.shift_id = ? AND o.status = 'PAID' GROUP BY p.method ORDER BY p.method
            """, (rs, i) -> new Line(rs.getString(1), rs.getInt(2), rs.getLong(3)), shiftId);
        List<Line> discs = jdbc.query("""
            SELECT coalesce(discount_name, discount_type), count(*), sum(discount) FROM orders
            WHERE shift_id = ? AND status = 'PAID' AND discount > 0 GROUP BY 1 ORDER BY 3 DESC
            """, (rs, i) -> new Line(rs.getString(1), rs.getInt(2), rs.getLong(3)), shiftId);
        long cashSales = pays.stream().filter(p -> p.label().equals("CASH")).mapToLong(Line::amount).sum();
        long in = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM cash_movements WHERE shift_id = ? AND kind = 'IN'", Long.class, shiftId);
        long out = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM cash_movements WHERE shift_id = ? AND kind = 'OUT'", Long.class, shiftId);

        long opening = ((Number) s.get("opening_cash")).longValue();
        boolean closed = s.get("closed_at") != null;
        long expected = s.get("expected_cash") != null ? ((Number) s.get("expected_cash")).longValue() : opening + cashSales + in - out;
        Long counted = s.get("counted_cash") == null ? null : ((Number) s.get("counted_cash")).longValue();
        return new Reading(closed ? "Z" : "X", (Integer) s.get("z_no"), shiftId, ((Number) s.get("branch_id")).longValue(),
            (String) s.get("branch"), (Boolean) s.get("vat_registered"), (String) s.get("full_name"),
            ((java.sql.Timestamp) s.get("opened_at")).toInstant(), closed ? ((java.sql.Timestamp) s.get("closed_at")).toInstant() : null,
            ((Number) o.get("n")).intValue(), (Long) o.get("first_no"), (Long) o.get("last_no"),
            ((Number) o.get("gross")).longValue(), ((Number) o.get("disc")).longValue(), ((Number) o.get("net")).longValue(),
            ((Number) o.get("vatable")).longValue(), ((Number) o.get("vat")).longValue(),
            ((Number) rf.get("n")).intValue(), ((Number) rf.get("amount")).longValue(),
            pays, discs, opening, cashSales, in, out, expected, counted, counted == null ? null : counted - expected);
    }
}
