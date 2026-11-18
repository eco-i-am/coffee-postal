package ph.coffeepostal.pos.hr;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;

/** Employees of a branch (and HQ staff, who have no branch). A branch employee needs a 6-digit PIN to punch the time clock. */
@Service
public class EmployeeService {
    public record Employee(long id, Long branchId, String branch, String fullName, String position, String status, boolean hasPin) {}

    private static final String PIN_TAKEN = "Someone in this branch already uses that PIN. Pick another.";

    private final JdbcTemplate jdbc;
    private final PinCodec pins;

    public EmployeeService(JdbcTemplate jdbc, PinCodec pins) { this.jdbc = jdbc; this.pins = pins; }

    /** branchId null = everyone (HQ view). */
    public List<Employee> list(Long branchId) {
        return jdbc.query("""
            SELECT e.id, e.branch_id, b.name, e.full_name, e.position, e.status, e.pin_lookup IS NOT NULL
            FROM employees e LEFT JOIN branches b ON b.id = e.branch_id
            WHERE (?::bigint IS NULL OR e.branch_id = ?) ORDER BY e.status, e.full_name
            """, (rs, i) -> new Employee(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7)),
            branchId, branchId);
    }

    public Employee get(long id) {
        List<Employee> r = jdbc.query("""
            SELECT e.id, e.branch_id, b.name, e.full_name, e.position, e.status, e.pin_lookup IS NOT NULL
            FROM employees e LEFT JOIN branches b ON b.id = e.branch_id WHERE e.id = ?
            """, (rs, i) -> new Employee(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7)), id);
        return r.isEmpty() ? null : r.get(0);
    }

    /** A branch employee must have a PIN; HQ staff (branchId null) have none. */
    @Transactional
    public long create(Long branchId, String fullName, String position, String pin) {
        if (branchId != null) PinCodec.validate(pin, "pin");
        String salt = pin == null ? null : pins.newSalt();
        try {
            return jdbc.queryForObject("""
                INSERT INTO employees(branch_id, full_name, position, pin_lookup, pin_salt, pin_check) VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, branchId, fullName.trim(), blank(position),
                branchId == null || pin == null ? null : pins.lookup(branchId, pin), salt, pin == null ? null : pins.check(salt, pin));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, PIN_TAKEN, Map.of("pin", "Already used."));
        }
    }

    @Transactional
    public void update(long id, String fullName, String position, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick active or inactive.");
        try {
            int n = jdbc.update("UPDATE employees SET full_name = ?, position = ?, status = ?, updated_at = now() WHERE id = ?",
                fullName.trim(), blank(position), status, id);
            if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That employee no longer exists.");
        } catch (DuplicateKeyException e) {
            // switching someone back on whose old PIN has since been given to another person
            throw new ApiException(HttpStatus.CONFLICT, "Their old PIN is now used by someone else. Set a new PIN first.");
        }
    }

    @Transactional
    public void setPin(long id, long branchId, String pin) {
        PinCodec.validate(pin, "pin");
        String salt = pins.newSalt();
        try {
            int n = jdbc.update("UPDATE employees SET pin_lookup = ?, pin_salt = ?, pin_check = ?, updated_at = now() WHERE id = ?",
                pins.lookup(branchId, pin), salt, pins.check(salt, pin), id);
            if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That employee no longer exists.");
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, PIN_TAKEN, Map.of("pin", "Already used."));
        }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
