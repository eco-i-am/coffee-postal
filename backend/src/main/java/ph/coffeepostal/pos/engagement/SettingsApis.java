package ph.coffeepostal.pos.engagement;

import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/**
 * Settings. HQ edits the company details, the receipt text and the PO rules; a branch edits its own address and phone.
 * Anything not set yet takes the default below, so the app works before anyone opens this page.
 */
class SettingsApis {
    static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("company_name", "Coffee Postal");
        DEFAULTS.put("company_address", "");
        DEFAULTS.put("company_tin", "");
        DEFAULTS.put("receipt_footer", "Thank you! Your affordable freshly brewed coffee destination.");
        DEFAULTS.put("receipt_note", "Not an official receipt.");
        DEFAULTS.put("po_lead_time_hours", "48");
        DEFAULTS.put("clock_shift_start", "09:00");
        DEFAULTS.put("clock_late_grace_minutes", "15");
    }

    record Receipt(String companyName, String footer, String note) {}
    record Profile(long id, String code, String name, String address, String phone, String tin, boolean vatRegistered) {}
    record ProfileReq(@Size(max = 300) String address, @Size(max = 40) String phone) {}

    @Service
    static class Svc {
        private final JdbcTemplate jdbc;

        Svc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        Map<String, String> all() {
            Map<String, String> out = new LinkedHashMap<>(DEFAULTS);
            jdbc.query("SELECT key, value FROM settings", rs -> { if (DEFAULTS.containsKey(rs.getString(1))) out.put(rs.getString(1), rs.getString(2)); });
            return out;
        }

        @Transactional
        void save(String username, Map<String, String> changes) {
            for (var e : changes.entrySet()) {
                String k = e.getKey();
                if (!DEFAULTS.containsKey(k)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown setting: " + k);
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.length() > 500) throw new ApiException(HttpStatus.BAD_REQUEST, "That is too long.", Map.of(k, "Keep it under 500 characters."));
                if (k.equals("clock_shift_start") && !v.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Use a time like 09:00.", Map.of(k, "Use a time like 09:00."));
                }
                if (k.equals("po_lead_time_hours") || k.equals("clock_late_grace_minutes")) {
                    if (!v.matches("\\d{1,4}")) throw new ApiException(HttpStatus.BAD_REQUEST, "Use a whole number.", Map.of(k, "Use a whole number, such as 48."));
                }
                if (k.equals("company_name") && v.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "The company name cannot be empty.", Map.of(k, "Required."));
                jdbc.update("""
                    INSERT INTO settings(key, value, updated_at, updated_by) VALUES (?, ?, now(), ?)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by
                    """, k, v, username);
            }
        }
    }

    @RestController
    @RequestMapping("/api/admin/settings")
    static class AdminApi {
        private final Svc svc;
        private final AuditService audit;

        AdminApi(Svc svc, AuditService audit) { this.svc = svc; this.audit = audit; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_settings_admin_view')")
        Map<String, String> get() { return svc.all(); }

        @PutMapping
        @PreAuthorize("hasAuthority('PERM_settings_admin_edit')")
        Map<String, String> put(@RequestBody Map<String, String> changes, Principal who) {
            svc.save(who.getName(), changes);
            audit.record(who.getName(), null, "SETTINGS_UPDATE", "settings", "hq", null, "{\"keys\":\"" + String.join(",", changes.keySet()).replace("\"", "'") + "\"}", null);
            return svc.all();
        }
    }

    /** What every signed-in screen needs: the receipt wording. */
    @RestController
    @RequestMapping("/api/settings")
    static class PublicApi {
        private final Svc svc;

        PublicApi(Svc svc) { this.svc = svc; }

        @GetMapping("/receipt")
        Receipt receipt() {
            Map<String, String> s = svc.all();
            return new Receipt(s.get("company_name"), s.get("receipt_footer"), s.get("receipt_note"));
        }
    }

    @RestController
    @RequestMapping("/api/branch/settings")
    static class BranchApi {
        private final JdbcTemplate jdbc;
        private final OrderService orders;
        private final AuditService audit;

        BranchApi(JdbcTemplate jdbc, OrderService orders, AuditService audit) { this.jdbc = jdbc; this.orders = orders; this.audit = audit; }

        private Profile profile(long branchId) {
            return jdbc.queryForObject("SELECT id, code, name, address, phone, tin, vat_registered FROM branches WHERE id = ?",
                (rs, i) -> new Profile(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7)), branchId);
        }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_branch_settings_view')")
        Profile get(Principal who) { return profile(orders.whoIs(who.getName()).branchId()); }

        @PutMapping
        @PreAuthorize("hasAuthority('PERM_branch_settings_edit')")
        Profile put(@jakarta.validation.Valid @RequestBody ProfileReq r, Principal who) {
            long branch = orders.whoIs(who.getName()).branchId();
            jdbc.update("UPDATE branches SET address = ?, phone = ?, updated_at = now() WHERE id = ?",
                r.address() == null || r.address().isBlank() ? null : r.address().trim(), r.phone() == null || r.phone().isBlank() ? null : r.phone().trim(), branch);
            audit.record(who.getName(), branch, "BRANCH_PROFILE_UPDATE", "branch", String.valueOf(branch), null, null, null);
            return profile(branch);
        }
    }
}
