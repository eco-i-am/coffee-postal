package ph.coffeepostal.pos.hr;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.sales.ManagerApproval;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** Who was in and for how long, and manager corrections (PIN and a reason; the original time stays in the audit log). */
@Service
public class AttendanceService {
    static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    /** status: OK, STILL_IN, FORGOT_OUT (a time in with no time out), NO_TIME_IN (a time out with no time in). */
    public record Session(long employeeId, String employee, String branch, LocalDate date, Instant inAt, Instant outAt,
                          Long inLogId, Long outLogId, Long minutes, String status, List<String> notes) {}
    public record Report(String from, String to, List<Session> sessions, long totalMinutes) {}

    private record Log(long id, long employeeId, String employee, String branch, String kind, Instant at, String flag, boolean corrected) {}

    private final JdbcTemplate jdbc;
    private final ManagerApproval approval;
    private final AuditService audit;

    public AttendanceService(JdbcTemplate jdbc, ManagerApproval approval, AuditService audit) {
        this.jdbc = jdbc; this.approval = approval; this.audit = audit;
    }

    /** branchId null = every branch (HQ). from/to are Manila calendar days, both included. */
    public Report report(Long branchId, Long employeeId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        if (Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > 92) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a range of 3 months or less.");
        Instant start = from.atStartOfDay(MANILA).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(MANILA).toInstant();
        List<Log> logs = jdbc.query("""
            SELECT l.id, l.employee_id, e.full_name, b.name, l.kind, l.punched_at, l.flag, l.corrected_by IS NOT NULL AND l.flag IS DISTINCT FROM 'MANUAL'
            FROM attendance_logs l JOIN employees e ON e.id = l.employee_id JOIN branches b ON b.id = l.branch_id
            WHERE l.punched_at >= ? AND l.punched_at < ? AND (?::bigint IS NULL OR l.branch_id = ?) AND (?::bigint IS NULL OR l.employee_id = ?)
            ORDER BY l.employee_id, l.punched_at, l.id
            """, (rs, i) -> new Log(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getBoolean(8)),
            java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), branchId, branchId, employeeId, employeeId);

        Map<Long, List<Log>> byPerson = new LinkedHashMap<>();
        for (Log l : logs) byPerson.computeIfAbsent(l.employeeId(), k -> new ArrayList<>()).add(l);
        List<Session> out = new ArrayList<>();
        Instant now = Instant.now();
        for (List<Log> mine : byPerson.values()) {
            Log open = null;
            for (Log l : mine) {
                if (l.kind().equals("IN")) {
                    if (open != null) out.add(session(open, null, now, true));    // a new time in: the earlier one was never closed
                    open = l;
                } else if (open != null) {
                    out.add(session(open, l, now, false));
                    open = null;
                } else {
                    out.add(session(null, l, now, false));
                }
            }
            if (open != null) out.add(session(open, null, now, false));
        }
        out.sort((a, b) -> {
            int c = b.date().compareTo(a.date());
            return c != 0 ? c : a.employee().compareToIgnoreCase(b.employee());
        });
        long total = out.stream().filter(s -> s.minutes() != null).mapToLong(Session::minutes).sum();
        return new Report(from.toString(), to.toString(), out, total);
    }

    private static Session session(Log in, Log out, Instant now, boolean superseded) {
        Log any = in != null ? in : out;
        List<String> notes = new ArrayList<>();
        for (Log l : new Log[] { in, out }) {
            if (l == null) continue;
            if ("CLOCK_SKEW".equals(l.flag())) notes.add("Tablet clock was off");
            if ("MANUAL".equals(l.flag())) notes.add("Added by a manager");
            if (l.corrected()) notes.add("Corrected by a manager");
        }
        String status;
        Long minutes = null;
        if (in == null) status = "NO_TIME_IN";
        else if (out == null) {
            status = superseded || Duration.between(in.at(), now).toHours() >= ClockService.FORGOT_OUT_HOURS ? "FORGOT_OUT" : "STILL_IN";
        } else {
            status = "OK";
            minutes = Math.max(0, Duration.between(in.at(), out.at()).toMinutes());
        }
        return new Session(any.employeeId(), any.employee(), any.branch(), any.at().atZone(MANILA).toLocalDate(),
            in == null ? null : in.at(), out == null ? null : out.at(), in == null ? null : in.id(), out == null ? null : out.id(),
            minutes, status, notes);
    }

    /** Changes the time of one punch. Needs a manager's PIN and a reason; the earlier time goes in the audit log. */
    @Transactional
    public void correct(OrderService.Who me, String username, long logId, Instant newAt, String reason, String pin) {
        String why = checkReason(reason);
        if (newAt == null || newAt.isAfter(Instant.now().plusSeconds(60))) throw new ApiException(HttpStatus.BAD_REQUEST, "The time cannot be in the future.");
        List<Instant> before = jdbc.query("SELECT punched_at FROM attendance_logs WHERE id = ? AND branch_id = ?",
            (rs, i) -> rs.getTimestamp(1).toInstant(), logId, me.branchId());
        if (before.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That punch was not found.");
        var approver = approval.approve(me, pin, "branch_employees");
        jdbc.update("UPDATE attendance_logs SET punched_at = ?, corrected_by = ?, correction_reason = ? WHERE id = ?",
            java.sql.Timestamp.from(newAt), approver.id(), why, logId);
        audit.record(username, me.branchId(), "ATTENDANCE_CORRECT", "attendance", String.valueOf(logId),
            "{\"punchedAt\":\"" + before.get(0) + "\"}", "{\"punchedAt\":\"" + newAt + "\",\"approvedBy\":" + approver.id() + "}", why);
    }

    /** Adds a punch that was missed (for example a forgotten time out). Needs a manager's PIN and a reason. */
    @Transactional
    public long addManual(OrderService.Who me, String username, long employeeId, String kind, Instant at, String reason, String pin) {
        String why = checkReason(reason);
        if (!"IN".equals(kind) && !"OUT".equals(kind)) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick time in or time out.");
        if (at == null || at.isAfter(Instant.now().plusSeconds(60))) throw new ApiException(HttpStatus.BAD_REQUEST, "The time cannot be in the future.");
        Integer n = jdbc.queryForObject("SELECT count(*) FROM employees WHERE id = ? AND branch_id = ?", Integer.class, employeeId, me.branchId());
        if (n == null || n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That employee was not found.");
        var approver = approval.approve(me, pin, "branch_employees");
        long id = jdbc.queryForObject("""
            INSERT INTO attendance_logs(employee_id, branch_id, kind, punched_at, device_time, source, flag, recorded_by, corrected_by, correction_reason)
            VALUES (?, ?, ?, ?, ?, 'ONLINE', 'MANUAL', ?, ?, ?) RETURNING id
            """, Long.class, employeeId, me.branchId(), kind, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at), me.userId(), approver.id(), why);
        audit.record(username, me.branchId(), "ATTENDANCE_ADD", "attendance", String.valueOf(id), null,
            "{\"employeeId\":" + employeeId + ",\"kind\":\"" + kind + "\",\"punchedAt\":\"" + at + "\",\"approvedBy\":" + approver.id() + "}", why);
        return id;
    }

    private static String checkReason(String reason) {
        if (reason == null || reason.trim().length() < 3) throw new ApiException(HttpStatus.BAD_REQUEST, "Say why (at least 3 characters).", Map.of("reason", "Say why."));
        return reason.trim().length() > 200 ? reason.trim().substring(0, 200) : reason.trim();
    }
}
