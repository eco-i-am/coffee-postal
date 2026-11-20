package ph.coffeepostal.pos.hr;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * The branch time clock. Staff type a 6-digit PIN; the server decides whether it is a time in or a time out
 * from that person's last punch, so a punch can never leave the record out of order.
 *  - Online: the server's clock is the time of record. A tablet clock that is far off is flagged.
 *  - Offline: the tablet recognises the PIN itself (salted check from the roster), keeps the punch with its own
 *    clock, and sends it later. That device time is then the time of record (up to 7 days back).
 *  - A time in with no time out for {@value #FORGOT_OUT_HOURS} hours counts as a forgotten time out; the next punch is a time in.
 * Deliberately NOT @Transactional as a whole: a wrong PIN must stay on record (and count toward the lockout)
 * even though the request fails.
 */
@Service
public class ClockService {
    public static final long FORGOT_OUT_HOURS = 16;
    static final int MAX_FAILURES = 10;       // wrong PINs per branch within the window
    static final int WINDOW_MINUTES = 10;
    static final long SKEW_SECONDS = 300;     // online punch: tablet clock more than 5 minutes off the server
    static final long OFFLINE_AFTER_SECONDS = 120;
    static final long MAX_OFFLINE_DAYS = 7;
    static final long DOUBLE_TAP_SECONDS = 20;

    /** Send either pin (online), or employeeId + check (a punch kept on the tablet while offline). */
    public record PunchReq(String pin, UUID clientId, Instant deviceTime, Long employeeId, String check, String photo, FaceMatch.Face face, boolean byFace) {}
    public record PunchRes(long employeeId, String name, String kind, Instant at, boolean offline, String flag, boolean photo, String faceStatus) {}
    public record RosterEntry(long id, String name, String position, String salt, String check, boolean in, boolean face) {}
    public record Roster(List<RosterEntry> employees, Instant serverTime, int photoDays) {}

    private record Emp(long id, String name) {}
    private record Last(String kind, Instant at) {}

    private final JdbcTemplate jdbc;
    private final PinCodec pins;
    private final TransactionTemplate tx;
    private final PhotoService photos;

    public ClockService(JdbcTemplate jdbc, PinCodec pins, PlatformTransactionManager tm, PhotoService photos) {
        this.jdbc = jdbc; this.pins = pins; this.tx = new TransactionTemplate(tm); this.photos = photos;
    }

    /** Active branch employees with a PIN, for the clock screen and for its offline copy. No PINs in here. */
    public Roster roster(long branchId) {
        List<RosterEntry> list = jdbc.query("""
            SELECT e.id, e.full_name, e.position, e.pin_salt, e.pin_check,
                   coalesce((SELECT l.kind = 'IN' AND l.punched_at > now() - make_interval(hours => ?)
                             FROM attendance_logs l WHERE l.employee_id = e.id ORDER BY l.punched_at DESC, l.id DESC LIMIT 1), FALSE) AS is_in,
                   (e.face_template IS NOT NULL) AS has_face
            FROM employees e WHERE e.branch_id = ? AND e.status = 'ACTIVE' AND (e.pin_lookup IS NOT NULL OR e.face_template IS NOT NULL) ORDER BY e.full_name
            """, (rs, i) -> new RosterEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7)),
            (int) FORGOT_OUT_HOURS, branchId);
        return new Roster(list, Instant.now(), photos.retentionDays());
    }

    public PunchRes punch(long branchId, long recordedBy, PunchReq r) {
        Instant now = Instant.now();
        if (r.clientId() != null) {
            PunchRes seen = existing(r.clientId(), branchId);
            if (seen != null) return seen;   // the same punch sent twice
        }

        Emp who;
        if (r.byFace() && r.employeeId() != null) {
            // Tapped a name and looked at the camera: the face stands in for the PIN, and must be a close match to the saved one.
            checkLockout(branchId);
            List<Emp> found = jdbc.query("SELECT id, full_name FROM employees WHERE id = ? AND branch_id = ? AND status = 'ACTIVE'",
                (rs, i) -> new Emp(rs.getLong(1), rs.getString(2)), r.employeeId(), branchId);
            if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That person is not on this branch's clock.");
            double[] saved = enrolledFace(found.get(0).id());
            if (saved == null) throw new ApiException(HttpStatus.FORBIDDEN, "No face is saved for you yet. Use your PIN.");
            FaceMatch.Result seen = FaceMatch.check(saved, r.face());
            if (!"MATCH".equals(seen.status()) || seen.score() > FaceMatch.LOGIN_MAX) {
                jdbc.update("INSERT INTO clock_attempts(branch_id, success) VALUES (?, FALSE)", branchId);
                throw new ApiException(HttpStatus.FORBIDDEN, "The camera did not recognise you. Try again in good light, or use your PIN.");
            }
            who = found.get(0);
        } else if (r.pin() != null) {
            checkLockout(branchId);
            List<Emp> found = r.pin().matches("\\d{6}")
                ? jdbc.query("SELECT id, full_name FROM employees WHERE branch_id = ? AND pin_lookup = ? AND status = 'ACTIVE'",
                    (rs, i) -> new Emp(rs.getLong(1), rs.getString(2)), branchId, pins.lookup(branchId, r.pin()))
                : List.of();
            if (found.isEmpty()) {
                jdbc.update("INSERT INTO clock_attempts(branch_id, success) VALUES (?, FALSE)", branchId);
                throw new ApiException(HttpStatus.FORBIDDEN, "That PIN is not right.");
            }
            who = found.get(0);
        } else if (r.employeeId() != null && r.check() != null) {
            // A punch kept on the tablet. The check proves the tablet had the employee's current PIN.
            List<Object[]> rows = jdbc.query("SELECT id, full_name, pin_check FROM employees WHERE id = ? AND branch_id = ? AND status = 'ACTIVE'",
                (rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getString(3) }, r.employeeId(), branchId);
            if (rows.isEmpty() || rows.get(0)[2] == null
                    || !MessageDigest.isEqual(((String) rows.get(0)[2]).getBytes(), r.check().getBytes())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This employee's PIN changed or they are no longer active. Ask a manager to add the time.");
            }
            who = new Emp((Long) rows.get(0)[0], (String) rows.get(0)[1]);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter your PIN.");
        }

        Instant device = r.deviceTime() == null ? now : r.deviceTime();
        boolean offline = r.deviceTime() != null && device.isBefore(now.minusSeconds(OFFLINE_AFTER_SECONDS));
        Instant at = now;
        String flag = null;
        if (offline) {
            if (device.isBefore(now.minus(Duration.ofDays(MAX_OFFLINE_DAYS)))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "This punch is more than " + MAX_OFFLINE_DAYS + " days old. Ask a manager to add the time.");
            }
            at = device;
        } else if (Math.abs(Duration.between(device, now).getSeconds()) > SKEW_SECONDS) {
            flag = "CLOCK_SKEW";
        }

        final Instant punchedAt = at;
        final String punchFlag = flag;
        final FaceMatch.Result face = FaceMatch.check(enrolledFace(who.id()), r.face());   // flags only; never stops the punch
        final byte[] photo = PhotoService.decode(r.photo());   // every punch, in or out, keeps its photo; a bad photo never stops the punch
        try {
            return tx.execute(status -> {
                jdbc.queryForObject("SELECT id FROM employees WHERE id = ? FOR UPDATE", Long.class, who.id());
                List<Last> last = jdbc.query("""
                    SELECT kind, punched_at FROM attendance_logs WHERE employee_id = ? AND punched_at <= ?
                    ORDER BY punched_at DESC, id DESC LIMIT 1
                    """, (rs, i) -> new Last(rs.getString(1), rs.getTimestamp(2).toInstant()), who.id(), java.sql.Timestamp.from(punchedAt));
                String kind = "IN";
                if (!last.isEmpty()) {
                    Duration gap = Duration.between(last.get(0).at(), punchedAt);
                    if (gap.getSeconds() < DOUBLE_TAP_SECONDS) throw new ApiException(HttpStatus.CONFLICT, "Already recorded a moment ago.");
                    if (last.get(0).kind().equals("IN") && gap.toHours() < FORGOT_OUT_HOURS) kind = "OUT";
                }
                long logId = jdbc.queryForObject("""
                    INSERT INTO attendance_logs(employee_id, branch_id, kind, punched_at, device_time, source, client_id, flag, recorded_by, face_status, face_score)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, who.id(), branchId, kind, java.sql.Timestamp.from(punchedAt), java.sql.Timestamp.from(device),
                    offline ? "OFFLINE" : "ONLINE", r.clientId(), punchFlag, recordedBy, face.status(), face.score());
                boolean kept = photo != null;
                if (kept) photos.store(logId, photo);
                return new PunchRes(who.id(), who.name(), kind, punchedAt, offline, punchFlag, kept, face.status());
            });
        } catch (DuplicateKeyException e) {
            PunchRes seen = r.clientId() == null ? null : existing(r.clientId(), branchId);
            if (seen != null) return seen;
            throw e;
        }
    }

    private void checkLockout(long branchId) {
        Integer failures = jdbc.queryForObject("""
            SELECT count(*) FROM clock_attempts WHERE branch_id = ? AND NOT success AND at > now() - make_interval(mins => ?)
            """, Integer.class, branchId, WINDOW_MINUTES);
        if (failures != null && failures >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong tries. Wait " + WINDOW_MINUTES + " minutes, or ask a manager.");
        }
    }

    /** The enrolled face of an employee, or null if none is on file. */
    private double[] enrolledFace(long employeeId) {
        List<String> r = jdbc.query("SELECT face_template::text FROM employees WHERE id = ?", (rs, i) -> rs.getString(1), employeeId);
        return r.isEmpty() ? null : FaceMatch.parse(r.get(0));
    }

    private PunchRes existing(UUID clientId, long branchId) {
        List<PunchRes> r = jdbc.query("""
            SELECT l.employee_id, e.full_name, l.kind, l.punched_at, l.source, l.flag,
                   EXISTS (SELECT 1 FROM attendance_photos p WHERE p.log_id = l.id), l.face_status
            FROM attendance_logs l JOIN employees e ON e.id = l.employee_id WHERE l.client_id = ? AND l.branch_id = ?
            """, (rs, i) -> new PunchRes(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
                "OFFLINE".equals(rs.getString(5)), rs.getString(6), rs.getBoolean(7), rs.getString(8)), clientId, branchId);
        return r.isEmpty() ? null : r.get(0);
    }
}
