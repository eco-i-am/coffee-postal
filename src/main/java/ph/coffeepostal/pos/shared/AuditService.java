package ph.coffeepostal.pos.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Writes the audit trail: who did what, when, with before/after snapshots.
 * Money, stock, price, role and branch changes must all go through here.
 * before/after are pre-serialised JSON strings (or null).
 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String username, Long branchId, String action, String entity,
                       String entityId, String beforeJson, String afterJson, String reason) {
        jdbc.update("""
            INSERT INTO audit_logs(user_id, branch_id, action, entity, entity_id, before_json, after_json, reason)
            VALUES ((SELECT id FROM users WHERE username = ?), ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """, username, branchId, action, entity, entityId, beforeJson, afterJson, reason);
    }
}
