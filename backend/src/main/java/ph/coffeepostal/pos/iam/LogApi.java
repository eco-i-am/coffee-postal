package ph.coffeepostal.pos.iam;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.reporting.ReportService;

/** HQ-only, read-only views of the audit trail and of who signed in (or failed to). Newest first, 50 per page. */
@RestController
@RequestMapping("/api/admin/logs")
class LogApi {
    static final int PAGE = 50;
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    record AuditRow(long id, String at, String user, String branch, String action, String entity, String entityId,
                    String before, String after, String reason) {}
    record LoginRow(long id, String at, String username, String fullName, boolean success, String ip, String device) {}
    record Page<T>(long total, int page, int pageSize, List<T> rows) {}

    private final JdbcTemplate jdbc;
    LogApi(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Timestamp ts(java.time.LocalDate d) { return Timestamp.from(d.atStartOfDay(MANILA).toInstant()); }
    private static String like(String s) { return "%" + s.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('PERM_logs_view')")
    Page<AuditRow> audit(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                         @RequestParam(required = false) String user, @RequestParam(required = false) String action,
                         @RequestParam(defaultValue = "0") int page) {
        var r = ReportService.range(from, to);
        StringBuilder where = new StringBuilder(" WHERE a.at >= ? AND a.at < ?");
        List<Object> args = new ArrayList<>(List.of(ts(r.from()), ts(r.to().plusDays(1))));
        if (user != null && !user.isBlank()) { where.append(" AND (lower(u.username) LIKE ? ESCAPE '\\' OR lower(u.full_name) LIKE ? ESCAPE '\\')"); args.add(like(user)); args.add(like(user)); }
        if (action != null && !action.isBlank()) { where.append(" AND a.action = ?"); args.add(action); }
        String from_ = " FROM audit_logs a LEFT JOIN users u ON u.id = a.user_id LEFT JOIN branches b ON b.id = a.branch_id";
        long total = jdbc.queryForObject("SELECT count(*)" + from_ + where, Long.class, args.toArray());
        int p = Math.max(0, page);
        args.add(PAGE); args.add(p * PAGE);
        List<AuditRow> rows = jdbc.query("""
            SELECT a.id, to_char(a.at AT TIME ZONE 'Asia/Manila', 'YYYY-MM-DD HH24:MI:SS') AS at, coalesce(u.full_name, 'System') AS who, b.name AS branch,
                   a.action, a.entity, a.entity_id, a.before_json::text, a.after_json::text, a.reason""" + from_ + where
            + " ORDER BY a.at DESC, a.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> new AuditRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)), args.toArray());
        return new Page<>(total, p, PAGE, rows);
    }

    /** The action names that exist, for the filter dropdown. */
    @GetMapping("/audit/actions")
    @PreAuthorize("hasAuthority('PERM_logs_view')")
    List<String> actions() { return jdbc.queryForList("SELECT DISTINCT action FROM audit_logs ORDER BY action", String.class); }

    @GetMapping("/logins")
    @PreAuthorize("hasAuthority('PERM_logs_view')")
    Page<LoginRow> logins(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                          @RequestParam(required = false) String user, @RequestParam(required = false) String result,
                          @RequestParam(defaultValue = "0") int page) {
        var r = ReportService.range(from, to);
        StringBuilder where = new StringBuilder(" WHERE h.at >= ? AND h.at < ?");
        List<Object> args = new ArrayList<>(List.of(ts(r.from()), ts(r.to().plusDays(1))));
        if (user != null && !user.isBlank()) { where.append(" AND (lower(h.username) LIKE ? ESCAPE '\\' OR lower(u.full_name) LIKE ? ESCAPE '\\')"); args.add(like(user)); args.add(like(user)); }
        if ("success".equals(result)) where.append(" AND h.success");
        if ("failed".equals(result)) where.append(" AND NOT h.success");
        String from_ = " FROM login_history h LEFT JOIN users u ON u.id = h.user_id";
        long total = jdbc.queryForObject("SELECT count(*)" + from_ + where, Long.class, args.toArray());
        int p = Math.max(0, page);
        args.add(PAGE); args.add(p * PAGE);
        List<LoginRow> rows = jdbc.query("""
            SELECT h.id, to_char(h.at AT TIME ZONE 'Asia/Manila', 'YYYY-MM-DD HH24:MI:SS'), h.username, u.full_name, h.success, h.ip, h.user_agent""" + from_ + where
            + " ORDER BY h.at DESC, h.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> new LoginRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6), rs.getString(7)), args.toArray());
        return new Page<>(total, p, PAGE, rows);
    }
}
