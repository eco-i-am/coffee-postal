package ph.coffeepostal.pos.reporting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.finance.InvoiceService;

/** HQ dashboard: today across all branches, what branches owe, and what needs HQ's attention. */
@RestController
@RequestMapping("/api/admin/dashboard")
class AdminDashboardApi {
    record BranchSales(long branchId, String branch, int orders, long sales) {}
    record Dashboard(String date, int orders, long sales, List<BranchSales> byBranch, InvoiceService.Summary receivables,
                     int poToReview, int poToSend) {}

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    private final JdbcTemplate jdbc;
    private final InvoiceService invoices;

    AdminDashboardApi(JdbcTemplate jdbc, InvoiceService invoices) { this.jdbc = jdbc; this.invoices = invoices; }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    Dashboard get() {
        LocalDate day = LocalDate.now(MANILA);
        var from = java.sql.Timestamp.from(day.atStartOfDay(MANILA).toInstant());
        var to = java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(MANILA).toInstant());
        List<BranchSales> by = jdbc.query("""
            SELECT b.id, b.name, count(o.id) AS n, coalesce(sum(o.total), 0) AS s
            FROM branches b LEFT JOIN orders o ON o.branch_id = b.id AND o.status = 'PAID' AND o.created_at >= ? AND o.created_at < ?
            WHERE b.active GROUP BY b.id, b.name ORDER BY s DESC, b.name
            """, (rs, i) -> new BranchSales(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)), from, to);
        int review = jdbc.queryForObject("SELECT count(*) FROM branch_pos WHERE status = 'REQUESTED'", Integer.class);
        int send = jdbc.queryForObject("SELECT count(*) FROM branch_pos WHERE status = 'APPROVED'", Integer.class);
        return new Dashboard(day.toString(), by.stream().mapToInt(BranchSales::orders).sum(), by.stream().mapToLong(BranchSales::sales).sum(),
            by, invoices.summary(null), review, send);
    }
}
