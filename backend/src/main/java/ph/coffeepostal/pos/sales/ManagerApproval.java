package ph.coffeepostal.pos.sales;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * A manager's PIN approves a sensitive action (refund, large discount) while the cashier stays signed in.
 * Runs in its own transaction so a wrong PIN stays on record (and counts toward the lockout)
 * even when the caller's transaction is rolled back.
 */
@Service
public class ManagerApproval {
    static final int MAX_FAILURES = 5;      // per branch, in the window below
    static final int WINDOW_MINUTES = 10;

    public record Approver(long id, String name) {}
    private record Candidate(long id, String name, String pinHash) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public ManagerApproval(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    /**
     * @param module the permission module the approver needs (can_add), for example "refunds"
     * @throws ApiException 403 for a wrong PIN, 429 after too many wrong PINs in the branch
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public Approver approve(OrderService.Who me, String pin, String module) {
        Integer failures = jdbc.queryForObject("""
            SELECT count(*) FROM pin_attempts WHERE branch_id = ? AND NOT success AND at > now() - make_interval(mins => ?)
            """, Integer.class, me.branchId(), WINDOW_MINUTES);
        if (failures != null && failures >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong PINs. Wait " + WINDOW_MINUTES + " minutes, or ask a manager to do it from their own screen.");
        }
        List<Candidate> candidates = jdbc.query("""
            SELECT u.id, u.full_name, u.pin_hash FROM users u
            JOIN user_branches ub ON ub.user_id = u.id
            JOIN role_permissions rp ON rp.role_id = u.role_id AND rp.module = ? AND rp.can_add
            WHERE ub.branch_id = ? AND u.pin_hash IS NOT NULL AND u.status = 'ACTIVE'
            """, (rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3)), module, me.branchId());
        Candidate ok = pin == null ? null : candidates.stream().filter(a -> encoder.matches(pin, a.pinHash())).findFirst().orElse(null);
        jdbc.update("INSERT INTO pin_attempts(branch_id, user_id, success) VALUES (?, ?, ?)", me.branchId(), me.userId(), ok != null);
        if (ok == null) throw new ApiException(HttpStatus.FORBIDDEN, "That manager PIN is not right.");
        return new Approver(ok.id(), ok.name());
    }
}
