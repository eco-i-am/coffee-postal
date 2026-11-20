package ph.coffeepostal.pos.engagement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;

/** Customer feedback: entered at the POS after a sale (a 1 to 5 rating and an optional comment), read by the branch and by HQ. */
class FeedbackApis {
    record Entry(long id, Long branchId, String branch, String orderRef, int rating, String comment, String by, Instant at) {}
    record Summary(int count, double average, int[] byRating) {}
    record Report(Summary summary, List<Entry> entries) {}
    record CreateReq(@Min(1) @Max(5) int rating, @Size(max = 500) String comment, @Size(max = 40) String orderRef) {}

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    @Service
    static class Svc {
        private final JdbcTemplate jdbc;

        Svc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        long add(long userId, long branchId, CreateReq r) {
            String comment = r.comment() == null || r.comment().isBlank() ? null : r.comment().trim();
            String ref = r.orderRef() == null || r.orderRef().isBlank() ? null : r.orderRef().trim();
            return jdbc.queryForObject("INSERT INTO feedback(branch_id, order_ref, rating, comment, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, branchId, ref, r.rating(), comment, userId);
        }

        /** branchId null = every branch. */
        Report report(Long branchId, LocalDate from, LocalDate to) {
            LocalDate end = to == null ? LocalDate.now(MANILA) : to;
            LocalDate start = from == null ? end.minusDays(29) : from;
            List<Entry> entries = jdbc.query("""
                SELECT f.id, f.branch_id, b.name, f.order_ref, f.rating, f.comment, u.full_name, f.created_at
                FROM feedback f JOIN branches b ON b.id = f.branch_id JOIN users u ON u.id = f.created_by
                WHERE (?::bigint IS NULL OR f.branch_id = ?)
                  AND (f.created_at AT TIME ZONE 'Asia/Manila')::date BETWEEN ? AND ?
                ORDER BY f.created_at DESC LIMIT 2000
                """, (rs, i) -> new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                    rs.getString(7), rs.getTimestamp(8).toInstant()), branchId, branchId, start, end);
            int[] by = new int[5];
            long sum = 0;
            for (Entry e : entries) { by[e.rating() - 1]++; sum += e.rating(); }
            double avg = entries.isEmpty() ? 0 : Math.round(sum * 10.0 / entries.size()) / 10.0;
            return new Report(new Summary(entries.size(), avg, by), entries);
        }
    }

    @RestController
    @RequestMapping("/api/pos/feedback")
    static class PosApi {
        private final Svc svc;
        private final OrderService orders;

        PosApi(Svc svc, OrderService orders) { this.svc = svc; this.orders = orders; }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_feedback_add')")
        Map<String, Object> add(@Valid @RequestBody CreateReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            return Map.of("id", svc.add(me.userId(), me.branchId(), r));
        }
    }

    @RestController
    @RequestMapping("/api/branch/feedback")
    static class BranchApi {
        private final Svc svc;
        private final OrderService orders;

        BranchApi(Svc svc, OrderService orders) { this.svc = svc; this.orders = orders; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_feedback_view')")
        Report list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to, Principal who) {
            return svc.report(orders.whoIs(who.getName()).branchId(), from, to);
        }
    }

    @RestController
    @RequestMapping("/api/admin/feedback")
    static class AdminApi {
        private final Svc svc;

        AdminApi(Svc svc) { this.svc = svc; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_feedback_admin_view')")
        Report list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Long branchId) {
            return svc.report(branchId, from, to);
        }
    }
}
