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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;

/** Support chat: a branch writes to HQ support, HQ replies, and every thread keeps its history. */
class SupportApis {
    record Thread(long id, Long branchId, String branch, String subject, String status, String by, Instant createdAt, Instant updatedAt,
                  int unread, String last) {}
    record Message(long id, boolean fromHq, String sender, String body, Instant createdAt) {}
    record Detail(Thread thread, List<Message> messages) {}
    record NewThreadReq(@NotBlank @Size(max = 120) String subject, @NotBlank @Size(max = 2000) String body) {}
    record MessageReq(@NotBlank @Size(max = 2000) String body) {}

    @Service
    static class Svc {
        private final JdbcTemplate jdbc;

        Svc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        /** side = true for HQ: unread counts what the branch wrote; false for a branch: what HQ wrote. */
        private String select(boolean hq) {
            return """
                SELECT t.id, t.branch_id, b.name AS branch, t.subject, t.status, u.full_name, t.created_at, t.updated_at,
                       %s AS unread,
                       coalesce((SELECT left(m.body, 90) FROM support_messages m WHERE m.thread_id = t.id ORDER BY m.id DESC LIMIT 1), '') AS last
                FROM support_threads t JOIN branches b ON b.id = t.branch_id JOIN users u ON u.id = t.created_by
                """.formatted(hq ? "t.hq_unread" : "t.branch_unread");
        }

        private Thread row(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new Thread(rs.getLong("id"), rs.getLong("branch_id"), rs.getString("branch"), rs.getString("subject"), rs.getString("status"),
                rs.getString("full_name"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("unread"), rs.getString("last"));
        }

        /** branchId null = every branch (HQ). */
        List<Thread> threads(boolean hq, Long branchId, String status) {
            return jdbc.query(select(hq) + " WHERE (?::bigint IS NULL OR t.branch_id = ?) AND (?::text IS NULL OR t.status = ?) ORDER BY t.updated_at DESC LIMIT 300",
                (rs, i) -> row(rs), branchId, branchId, status, status);
        }

        int unread(boolean hq, Long branchId) {
            return jdbc.queryForObject("SELECT coalesce(sum(" + (hq ? "hq_unread" : "branch_unread") + "), 0) FROM support_threads WHERE (?::bigint IS NULL OR branch_id = ?)",
                Integer.class, branchId, branchId);
        }

        /** Opens a thread and marks what this side had not seen as seen. A branch can only open its own. */
        @Transactional
        Detail open(boolean hq, Long branchId, long id) {
            List<Thread> t = jdbc.query(select(hq) + " WHERE t.id = ? AND (?::bigint IS NULL OR t.branch_id = ?)", (rs, i) -> row(rs), id, branchId, branchId);
            if (t.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That conversation was not found.");
            jdbc.update("UPDATE support_threads SET " + (hq ? "hq_unread" : "branch_unread") + " = 0 WHERE id = ?", id);
            List<Message> m = jdbc.query("""
                SELECT m.id, m.from_hq, u.full_name, m.body, m.created_at FROM support_messages m JOIN users u ON u.id = m.sender_id
                WHERE m.thread_id = ? ORDER BY m.id
                """, (rs, i) -> new Message(rs.getLong(1), rs.getBoolean(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), id);
            Thread seen = t.get(0);
            return new Detail(new Thread(seen.id(), seen.branchId(), seen.branch(), seen.subject(), seen.status(), seen.by(), seen.createdAt(), seen.updatedAt(), 0, seen.last()), m);
        }

        @Transactional
        long start(long userId, long branchId, NewThreadReq r) {
            long id = jdbc.queryForObject("INSERT INTO support_threads(branch_id, subject, created_by, hq_unread) VALUES (?, ?, ?, 1) RETURNING id",
                Long.class, branchId, r.subject().trim(), userId);
            jdbc.update("INSERT INTO support_messages(thread_id, sender_id, from_hq, body) VALUES (?, ?, FALSE, ?)", id, userId, r.body().trim());
            return id;
        }

        /** A reply from either side. A branch writing to a closed thread opens it again. */
        @Transactional
        void post(boolean hq, Long branchId, long userId, long id, String body) {
            int n = jdbc.update("""
                UPDATE support_threads SET updated_at = now(), status = CASE WHEN ? THEN status ELSE 'OPEN' END,
                       hq_unread = hq_unread + CASE WHEN ? THEN 0 ELSE 1 END, branch_unread = branch_unread + CASE WHEN ? THEN 1 ELSE 0 END
                WHERE id = ? AND (?::bigint IS NULL OR branch_id = ?)
                """, hq, hq, hq, id, branchId, branchId);
            if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That conversation was not found.");
            jdbc.update("INSERT INTO support_messages(thread_id, sender_id, from_hq, body) VALUES (?, ?, ?, ?)", id, userId, hq, body.trim());
        }

        @Transactional
        void setStatus(long id, String status) {
            if (jdbc.update("UPDATE support_threads SET status = ? WHERE id = ?", status, id) == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That conversation was not found.");
        }

        long userId(String username) {
            return jdbc.query("SELECT id FROM users WHERE username = ?", (rs, i) -> rs.getLong(1), username).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Your account was not found."));
        }
    }

    @RestController
    @RequestMapping("/api/admin/support")
    static class AdminApi {
        private final Svc svc;

        AdminApi(Svc svc) { this.svc = svc; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_support_view')")
        List<Thread> list(@org.springframework.web.bind.annotation.RequestParam(required = false) String status) {
            return svc.threads(true, null, status == null || status.isBlank() ? null : status.toUpperCase());
        }

        @GetMapping("/unread")
        @PreAuthorize("hasAuthority('PERM_support_view')")
        Map<String, Object> unread() { return Map.of("count", svc.unread(true, null)); }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_support_view')")
        Detail open(@PathVariable long id) { return svc.open(true, null, id); }

        @PostMapping("/{id}/messages")
        @PreAuthorize("hasAuthority('PERM_support_add')")
        Map<String, Object> reply(@PathVariable long id, @Valid @RequestBody MessageReq r, Principal who) {
            svc.post(true, null, svc.userId(who.getName()), id, r.body());
            return Map.of("id", id);
        }

        @PostMapping("/{id}/close")
        @PreAuthorize("hasAuthority('PERM_support_edit')")
        Map<String, Object> close(@PathVariable long id) { svc.setStatus(id, "CLOSED"); return Map.of("id", id); }

        @PostMapping("/{id}/reopen")
        @PreAuthorize("hasAuthority('PERM_support_edit')")
        Map<String, Object> reopen(@PathVariable long id) { svc.setStatus(id, "OPEN"); return Map.of("id", id); }
    }

    @RestController
    @RequestMapping("/api/branch/support")
    static class BranchApi {
        private final Svc svc;
        private final OrderService orders;

        BranchApi(Svc svc, OrderService orders) { this.svc = svc; this.orders = orders; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_branch_support_view')")
        List<Thread> list(Principal who) { return svc.threads(false, orders.whoIs(who.getName()).branchId(), null); }

        @GetMapping("/unread")
        @PreAuthorize("hasAuthority('PERM_branch_support_view')")
        Map<String, Object> unread(Principal who) { return Map.of("count", svc.unread(false, orders.whoIs(who.getName()).branchId())); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_branch_support_add')")
        Map<String, Object> start(@Valid @RequestBody NewThreadReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            return Map.of("id", svc.start(me.userId(), me.branchId(), r));
        }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_branch_support_view')")
        Detail open(@PathVariable long id, Principal who) { return svc.open(false, orders.whoIs(who.getName()).branchId(), id); }

        @PostMapping("/{id}/messages")
        @PreAuthorize("hasAuthority('PERM_branch_support_add')")
        Map<String, Object> reply(@PathVariable long id, @Valid @RequestBody MessageReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            svc.post(false, me.branchId(), me.userId(), id, r.body());
            return Map.of("id", id);
        }
    }
}
