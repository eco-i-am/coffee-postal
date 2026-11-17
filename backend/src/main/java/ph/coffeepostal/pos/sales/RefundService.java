package ph.coffeepostal.pos.sales;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/**
 * Refunds need a manager's PIN. The cashier stays signed in; the PIN identifies who approved it.
 * Deliberately NOT @Transactional: a wrong PIN must stay on record even though the request fails.
 */
@Service
public class RefundService {
    static final int MAX_FAILURES = 5;      // per branch, in the window below
    static final int WINDOW_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final OrderService orders;
    private final AuditService audit;

    public RefundService(JdbcTemplate jdbc, PasswordEncoder encoder, OrderService orders, AuditService audit) {
        this.jdbc = jdbc; this.encoder = encoder; this.orders = orders; this.audit = audit;
    }

    private record Approver(long id, String name, String pinHash) {}

    public void refund(UUID orderId, String username, String reason, String pin) {
        var me = orders.whoIs(username);

        Integer failures = jdbc.queryForObject("""
            SELECT count(*) FROM pin_attempts WHERE branch_id = ? AND NOT success AND at > now() - make_interval(mins => ?)
            """, Integer.class, me.branchId(), WINDOW_MINUTES);
        if (failures != null && failures >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong PINs. Wait " + WINDOW_MINUTES + " minutes, or ask a manager to refund from their own screen.");
        }

        // people in this branch whose role may refund and who have a PIN
        List<Approver> approvers = jdbc.query("""
            SELECT u.id, u.full_name, u.pin_hash FROM users u
            JOIN user_branches ub ON ub.user_id = u.id
            JOIN role_permissions rp ON rp.role_id = u.role_id AND rp.module = 'refunds' AND rp.can_add
            WHERE ub.branch_id = ? AND u.pin_hash IS NOT NULL AND u.status = 'ACTIVE'
            """, (rs, i) -> new Approver(rs.getLong(1), rs.getString(2), rs.getString(3)), me.branchId());
        Approver ok = approvers.stream().filter(a -> encoder.matches(pin, a.pinHash())).findFirst().orElse(null);

        jdbc.update("INSERT INTO pin_attempts(branch_id, user_id, success) VALUES (?, ?, ?)", me.branchId(), me.userId(), ok != null);
        if (ok == null) {
            audit.record(username, me.branchId(), "REFUND_PIN_FAIL", "order", orderId.toString(), null, null, null);
            throw new ApiException(HttpStatus.FORBIDDEN, "That manager PIN is not right.");
        }

        int n = jdbc.update("""
            UPDATE orders SET status = 'REFUNDED', refund_reason = ?, refunded_at = now(), refunded_by = ?, refund_approved_by = ?
            WHERE id = ? AND branch_id = ? AND status = 'PAID'
            """, reason.trim(), me.userId(), ok.id(), orderId, me.branchId());
        if (n == 0) {
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ? AND branch_id = ?", Integer.class, orderId, me.branchId());
            throw exists != null && exists > 0
                ? new ApiException(HttpStatus.CONFLICT, "Only a paid order can be refunded. This one already was.")
                : new ApiException(HttpStatus.NOT_FOUND, "That order was not found.");
        }
        audit.record(username, me.branchId(), "ORDER_REFUND", "order", orderId.toString(), "{\"status\":\"PAID\"}",
            "{\"status\":\"REFUNDED\",\"approvedBy\":\"" + ok.name().replace("\"", "'") + "\"}", null);
    }
}
