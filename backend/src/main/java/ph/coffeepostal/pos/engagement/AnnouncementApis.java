package ph.coffeepostal.pos.engagement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** Announcements: HQ writes to all branches or chosen ones; branch users read them and mark them read. */
class AnnouncementApis {
    record Sent(long id, String title, String body, boolean allBranches, List<String> branches, String by, Instant createdAt,
                boolean removed, int readers) {}
    record Inbox(long id, String title, String body, String by, Instant createdAt, boolean read) {}
    record CreateReq(@NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 4000) String body, boolean allBranches, List<Long> branchIds) {}

    @Service
    static class Svc {
        private final JdbcTemplate jdbc;

        Svc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        long userId(String username) {
            return jdbc.query("SELECT id FROM users WHERE username = ?", (rs, i) -> rs.getLong(1), username).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Your account was not found."));
        }

        List<Sent> sent() {
            return jdbc.query("""
                SELECT a.id, a.title, a.body, a.all_branches, u.full_name, a.created_at, a.removed_at IS NOT NULL AS removed,
                       (SELECT count(*) FROM announcement_reads r WHERE r.announcement_id = a.id) AS readers,
                       coalesce((SELECT string_agg(b.name, ', ' ORDER BY b.name) FROM announcement_branches ab
                                 JOIN branches b ON b.id = ab.branch_id WHERE ab.announcement_id = a.id), '') AS names
                FROM announcements a JOIN users u ON u.id = a.created_by
                ORDER BY a.created_at DESC LIMIT 300
                """, (rs, i) -> new Sent(rs.getLong("id"), rs.getString("title"), rs.getString("body"), rs.getBoolean("all_branches"),
                    rs.getString("names").isEmpty() ? List.of() : List.of(rs.getString("names").split(", ")), rs.getString("full_name"),
                    rs.getTimestamp("created_at").toInstant(), rs.getBoolean("removed"), rs.getInt("readers")));
        }

        @Transactional
        long create(String username, CreateReq r) {
            boolean all = r.allBranches();
            List<Long> ids = r.branchIds() == null ? List.of() : r.branchIds().stream().distinct().toList();
            if (!all && ids.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick at least one branch, or send it to all branches.", Map.of("branchIds", "Pick a branch."));
            long id = jdbc.queryForObject("INSERT INTO announcements(title, body, all_branches, created_by) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, r.title().trim(), r.body().trim(), all, userId(username));
            if (!all) {
                for (long b : ids) {
                    try {
                        jdbc.update("INSERT INTO announcement_branches(announcement_id, branch_id) VALUES (?, ?)", id, b);
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "One of the chosen branches no longer exists.");
                    }
                }
            }
            return id;
        }

        @Transactional
        boolean remove(long id) {
            return jdbc.update("UPDATE announcements SET removed_at = now() WHERE id = ? AND removed_at IS NULL", id) > 0;
        }

        /** What this user can see: sent to all branches or to theirs, not taken down, newest first. */
        List<Inbox> inbox(long userId, long branchId) {
            return jdbc.query("""
                SELECT a.id, a.title, a.body, u.full_name, a.created_at,
                       EXISTS (SELECT 1 FROM announcement_reads r WHERE r.announcement_id = a.id AND r.user_id = ?) AS read
                FROM announcements a JOIN users u ON u.id = a.created_by
                WHERE a.removed_at IS NULL
                  AND (a.all_branches OR EXISTS (SELECT 1 FROM announcement_branches ab WHERE ab.announcement_id = a.id AND ab.branch_id = ?))
                ORDER BY a.created_at DESC LIMIT 200
                """, (rs, i) -> new Inbox(rs.getLong("id"), rs.getString("title"), rs.getString("body"), rs.getString("full_name"),
                    rs.getTimestamp("created_at").toInstant(), rs.getBoolean("read")), userId, branchId);
        }

        @Transactional
        void markRead(long userId, long branchId, long id) {
            // only something this user can see can be marked
            if (inbox(userId, branchId).stream().noneMatch((a) -> a.id() == id)) throw new ApiException(HttpStatus.NOT_FOUND, "That announcement was not found.");
            jdbc.update("INSERT INTO announcement_reads(announcement_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, userId);
        }
    }

    @RestController
    @RequestMapping("/api/admin/announcements")
    static class AdminApi {
        private final Svc svc;
        private final AuditService audit;

        AdminApi(Svc svc, AuditService audit) { this.svc = svc; this.audit = audit; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_announcements_view')")
        List<Sent> list() { return svc.sent(); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_announcements_add')")
        Map<String, Object> create(@Valid @RequestBody CreateReq r, Principal who) {
            long id = svc.create(who.getName(), r);
            audit.record(who.getName(), null, "ANNOUNCEMENT_CREATE", "announcement", String.valueOf(id), null,
                "{\"all\":" + r.allBranches() + "}", null);
            return Map.of("id", id);
        }

        @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_announcements_delete')")
        Map<String, Object> remove(@PathVariable long id, Principal who) {
            if (!svc.remove(id)) throw new ApiException(HttpStatus.NOT_FOUND, "That announcement was not found, or is already taken down.");
            audit.record(who.getName(), null, "ANNOUNCEMENT_REMOVE", "announcement", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }
    }

    @RestController
    @RequestMapping("/api/branch/announcements")
    static class BranchApi {
        private final Svc svc;
        private final OrderService orders;

        BranchApi(Svc svc, OrderService orders) { this.svc = svc; this.orders = orders; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_branch_announcements_view')")
        List<Inbox> inbox(Principal who) {
            var me = orders.whoIs(who.getName());
            return svc.inbox(me.userId(), me.branchId());
        }

        @PostMapping("/{id}/read")
        @PreAuthorize("hasAuthority('PERM_branch_announcements_view')")
        Map<String, Object> read(@PathVariable long id, Principal who) {
            var me = orders.whoIs(who.getName());
            svc.markRead(me.userId(), me.branchId(), id);
            return Map.of("id", id);
        }
    }
}
