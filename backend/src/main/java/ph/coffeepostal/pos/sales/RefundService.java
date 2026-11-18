package ph.coffeepostal.pos.sales;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/**
 * Refunds need a manager's PIN. The cashier stays signed in; the PIN identifies who approved it.
 * Deliberately NOT @Transactional: a wrong PIN must stay on record even though the request fails.
 */
@Service
public class RefundService {
    private final JdbcTemplate jdbc;
    private final ManagerApproval approval;
    private final OrderService orders;
    private final AuditService audit;

    public RefundService(JdbcTemplate jdbc, ManagerApproval approval, OrderService orders, AuditService audit) {
        this.jdbc = jdbc; this.approval = approval; this.orders = orders; this.audit = audit;
    }

    public void refund(UUID orderId, String username, String reason, String pin) {
        var me = orders.whoIs(username);

        ManagerApproval.Approver ok;
        try {
            ok = approval.approve(me, pin, "refunds");
        } catch (ApiException e) {
            if (e.status() == HttpStatus.FORBIDDEN) audit.record(username, me.branchId(), "REFUND_PIN_FAIL", "order", orderId.toString(), null, null, null);
            throw e;
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
