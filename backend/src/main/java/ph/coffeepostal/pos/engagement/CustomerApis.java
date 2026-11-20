package ph.coffeepostal.pos.engagement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/**
 * Branch customers: this branch's own list. Order history is the branch's paid orders that carry the same name
 * (the POS asks "for whom" on each order), so it is as good as that name is.
 */
@RestController
@RequestMapping("/api/branch/customers")
class CustomerApis {
    record Customer(long id, String name, String phone, String email, String notes, Instant createdAt, int orders, long spent, Instant lastOrder) {}
    record OrderRow(long orderNo, Instant at, long total, String status) {}
    record Req(@NotBlank @Size(max = 60) String name, @Size(max = 40) String phone, @Size(max = 120) String email, @Size(max = 300) String notes) {}

    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final AuditService audit;

    CustomerApis(JdbcTemplate jdbc, OrderService orders, AuditService audit) { this.jdbc = jdbc; this.orders = orders; this.audit = audit; }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_branch_customers_view')")
    List<Customer> list(@RequestParam(required = false) String q, Principal who) {
        long branch = orders.whoIs(who.getName()).branchId();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.query("""
            SELECT c.id, c.name, c.phone, c.email, c.notes, c.created_at,
                   count(o.id) AS orders, coalesce(sum(o.total), 0) AS spent, max(o.created_at) AS last_order
            FROM customers c
            LEFT JOIN orders o ON o.branch_id = c.branch_id AND o.status = 'PAID' AND lower(o.customer_name) = lower(c.name)
            WHERE c.branch_id = ? AND (?::text IS NULL OR lower(c.name) LIKE ? OR lower(coalesce(c.phone, '')) LIKE ? OR lower(coalesce(c.email, '')) LIKE ?)
            GROUP BY c.id ORDER BY lower(c.name) LIMIT 1000
            """, (rs, i) -> new Customer(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("email"), rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant(), rs.getInt("orders"), rs.getLong("spent"),
                rs.getTimestamp("last_order") == null ? null : rs.getTimestamp("last_order").toInstant()), branch, like, like, like, like);
    }

    @GetMapping("/{id}/orders")
    @PreAuthorize("hasAuthority('PERM_branch_customers_view')")
    List<OrderRow> history(@PathVariable long id, Principal who) {
        long branch = orders.whoIs(who.getName()).branchId();
        return jdbc.query("""
            SELECT o.order_no, o.created_at, o.total, o.status FROM orders o JOIN customers c ON c.branch_id = o.branch_id AND lower(c.name) = lower(o.customer_name)
            WHERE c.id = ? AND c.branch_id = ? ORDER BY o.created_at DESC LIMIT 50
            """, (rs, i) -> new OrderRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getLong(3), rs.getString(4)), id, branch);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_branch_customers_add')")
    Map<String, Object> create(@Valid @RequestBody Req r, Principal who) {
        var me = orders.whoIs(who.getName());
        try {
            long id = jdbc.queryForObject("INSERT INTO customers(branch_id, name, phone, email, notes, created_by) VALUES (?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                me.branchId(), r.name().trim(), blank(r.phone()), blank(r.email()), blank(r.notes()), me.userId());
            audit.record(who.getName(), me.branchId(), "CUSTOMER_CREATE", "customer", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "A customer with that name is already on the list.", Map.of("name", "Already on the list."));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_branch_customers_edit')")
    Map<String, Object> update(@PathVariable long id, @Valid @RequestBody Req r, Principal who) {
        long branch = orders.whoIs(who.getName()).branchId();
        try {
            int n = jdbc.update("UPDATE customers SET name = ?, phone = ?, email = ?, notes = ? WHERE id = ? AND branch_id = ?",
                r.name().trim(), blank(r.phone()), blank(r.email()), blank(r.notes()), id, branch);
            if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That customer was not found.");
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "A customer with that name is already on the list.", Map.of("name", "Already on the list."));
        }
        audit.record(who.getName(), branch, "CUSTOMER_UPDATE", "customer", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_branch_customers_delete')")
    Map<String, Object> delete(@PathVariable long id, Principal who) {
        long branch = orders.whoIs(who.getName()).branchId();
        if (jdbc.update("DELETE FROM customers WHERE id = ? AND branch_id = ?", id, branch) == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That customer was not found.");
        audit.record(who.getName(), branch, "CUSTOMER_DELETE", "customer", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }
}
