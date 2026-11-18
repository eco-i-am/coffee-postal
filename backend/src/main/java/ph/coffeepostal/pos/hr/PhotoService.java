package ph.coffeepostal.pos.hr;

import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The picture taken at each time in and time out. The tablet sends a small JPEG (about 320 px wide); the server checks that it is
 * one and keeps it. A bad or missing photo never stops a punch: the punch is recorded and the report says "no photo".
 * Photos are personal data, so they are deleted after {@code retentionDays}.
 */
@Service
public class PhotoService {
    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);
    static final int MAX_BYTES = 150_000;

    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public PhotoService(JdbcTemplate jdbc, @Value("${app.timeclock.photo-retention-days:90}") int retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = Math.max(1, retentionDays);
    }

    public int retentionDays() { return retentionDays; }

    /** The JPEG bytes from a base64 string (a data: prefix is tolerated), or null if there is none or it is not a usable JPEG. */
    public static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank() || base64.length() > MAX_BYTES * 2) return null;
        String raw = base64.startsWith("data:") ? base64.substring(base64.indexOf(',') + 1) : base64;
        try {
            byte[] b = Base64.getMimeDecoder().decode(raw);
            boolean jpeg = b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
            return jpeg && b.length <= MAX_BYTES ? b : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void store(long logId, byte[] jpeg) {
        jdbc.update("INSERT INTO attendance_photos(log_id, jpeg) VALUES (?, ?) ON CONFLICT (log_id) DO NOTHING", logId, jpeg);
    }

    /** The photo for a punch, only if that punch belongs to the branch (branchId null = any branch, for HQ). */
    public byte[] get(long logId, Long branchId) {
        List<byte[]> r = jdbc.query("""
            SELECT p.jpeg FROM attendance_photos p JOIN attendance_logs l ON l.id = p.log_id
            WHERE p.log_id = ? AND (?::bigint IS NULL OR l.branch_id = ?)
            """, (rs, i) -> rs.getBytes(1), logId, branchId, branchId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Every night: delete photos past the retention period. Returns how many were removed. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Manila")
    public int purge() {
        int n = jdbc.update("DELETE FROM attendance_photos WHERE taken_at < now() - make_interval(days => ?)", retentionDays);
        if (n > 0) log.info("Deleted {} attendance photos older than {} days", n, retentionDays);
        return n;
    }
}
