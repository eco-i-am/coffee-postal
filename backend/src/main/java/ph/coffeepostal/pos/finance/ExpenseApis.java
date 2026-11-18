package ph.coffeepostal.pos.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.reporting.ReportService;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.PesoInput;

/** Expenses: the branch records its own; HQ records its own and sees every branch's. */
class ExpenseApis {
    static final List<String> METHODS = List.of("CASH", "BANK", "GCASH", "MAYA", "CHECK");

    record Category(long id, String name, boolean active) {}
    record Expense(long id, Long branchId, String branch, long categoryId, String category, long amount, LocalDate date, String description,
                   String paidWith, String receiptNo, String by, Instant createdAt, boolean voided, String voidReason) {}
    record CreateReq(@NotNull Long categoryId, @NotBlank String amount, LocalDate date, @NotBlank @Size(max = 200) String description,
                     @NotBlank String paidWith, @Size(max = 40) String receiptNo, Long branchId) {}
    record VoidReq(@NotBlank @Size(max = 200) String reason) {}
    record CatReq(@NotBlank @Size(max = 60) String name, Boolean active) {}

    @Service
    static class Svc {
        private final JdbcTemplate jdbc;

        Svc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        long userId(String username) {
            return jdbc.query("SELECT id FROM users WHERE username = ?", (rs, i) -> rs.getLong(1), username).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Your account was not found."));
        }

        List<Category> categories(boolean onlyActive) {
            return jdbc.query("SELECT id, name, active FROM expense_categories" + (onlyActive ? " WHERE active" : "") + " ORDER BY lower(name)",
                (rs, i) -> new Category(rs.getLong(1), rs.getString(2), rs.getBoolean(3)));
        }

        @Transactional
        long addCategory(String name) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM expense_categories WHERE lower(name) = lower(?)", Integer.class, name);
            if (n != null && n > 0) throw new ApiException(HttpStatus.CONFLICT, "That category already exists.", Map.of("name", "Already used."));
            return jdbc.queryForObject("INSERT INTO expense_categories(name) VALUES (?) RETURNING id", Long.class, name);
        }

        @Transactional
        void editCategory(long id, String name, Boolean active) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM expense_categories WHERE lower(name) = lower(?) AND id <> ?", Integer.class, name, id);
            if (n != null && n > 0) throw new ApiException(HttpStatus.CONFLICT, "That category already exists.", Map.of("name", "Already used."));
            if (jdbc.update("UPDATE expense_categories SET name = ?, active = coalesce(?, active) WHERE id = ?", name, active, id) == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "That category no longer exists.");
            }
        }

        @Transactional
        long create(Long branchId, long userId, CreateReq r) {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Manila"));
            LocalDate date = r.date() == null ? today : r.date();
            if (date.isAfter(today)) throw new ApiException(HttpStatus.BAD_REQUEST, "The date cannot be in the future.", Map.of("date", "Not in the future."));
            if (!METHODS.contains(r.paidWith())) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick how it was paid.");
            long amount = PesoInput.parse(r.amount(), false, "amount", "Enter an amount like 1500 or 1500.50.");
            Integer cat = jdbc.queryForObject("SELECT count(*) FROM expense_categories WHERE id = ? AND active", Integer.class, r.categoryId());
            if (cat == null || cat == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a category.", Map.of("categoryId", "Pick one."));
            if (branchId != null) {
                Integer b = jdbc.queryForObject("SELECT count(*) FROM branches WHERE id = ?", Integer.class, branchId);
                if (b == null || b == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a branch or leave it blank for an HQ expense.");
            }
            return jdbc.queryForObject("""
                INSERT INTO expenses(branch_id, category_id, amount, expense_date, description, paid_with, receipt_no, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, branchId, r.categoryId(), amount, date, r.description().trim(), r.paidWith(),
                r.receiptNo() == null || r.receiptNo().isBlank() ? null : r.receiptNo().trim(), userId);
        }

        /** Voids an expense that is still live. branchId non-null = a branch user, who may only void their own branch's. */
        @Transactional
        void voidIt(long id, Long branchId, long userId, String reason) {
            int n = jdbc.update("""
                UPDATE expenses SET voided_at = now(), voided_by = ?, void_reason = ?
                WHERE id = ? AND voided_at IS NULL AND (?::bigint IS NULL OR branch_id = ?)
                """, userId, reason.trim(), id, branchId, branchId);
            if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That expense was not found, or it is already voided.");
        }

        /** branchId null = all (HQ, including HQ-only expenses). */
        List<Expense> list(Long branchId, ReportService.Range range, boolean includeVoided) {
            return jdbc.query("""
                SELECT e.id, e.branch_id, coalesce(b.name, 'Head office'), e.category_id, c.name, e.amount, e.expense_date, e.description, e.paid_with,
                       e.receipt_no, u.full_name, e.created_at, e.voided_at IS NOT NULL, e.void_reason
                FROM expenses e JOIN expense_categories c ON c.id = e.category_id JOIN users u ON u.id = e.created_by LEFT JOIN branches b ON b.id = e.branch_id
                WHERE (?::bigint IS NULL OR e.branch_id = ?) AND e.expense_date BETWEEN ? AND ? AND (? OR e.voided_at IS NULL)
                ORDER BY e.expense_date DESC, e.id DESC LIMIT 1000
                """, (rs, i) -> new Expense(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6),
                    rs.getDate(7).toLocalDate(), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant(),
                    rs.getBoolean(13), rs.getString(14)), branchId, branchId, java.sql.Date.valueOf(range.from()), java.sql.Date.valueOf(range.to()), includeVoided);
        }
    }

    @RestController
    @RequestMapping("/api/branch")
    static class BranchApi {
        private final Svc svc;
        private final OrderService orders;
        private final AuditService audit;

        BranchApi(Svc svc, OrderService orders, AuditService audit) { this.svc = svc; this.orders = orders; this.audit = audit; }

        @GetMapping("/expense-categories")
        @PreAuthorize("hasAuthority('PERM_branch_expenses_view')")
        List<Category> categories() { return svc.categories(true); }

        @GetMapping("/expenses")
        @PreAuthorize("hasAuthority('PERM_branch_expenses_view')")
        List<Expense> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
            return svc.list(orders.whoIs(who.getName()).branchId(), ReportService.range(from, to), true);
        }

        @PostMapping("/expenses")
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_branch_expenses_add')")
        Map<String, Object> create(@Valid @RequestBody CreateReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            long id = svc.create(me.branchId(), me.userId(), r);   // a branch user can only record for their own branch
            audit.record(who.getName(), me.branchId(), "EXPENSE_CREATE", "expense", String.valueOf(id), null, "{\"amount\":\"" + r.amount().replace("\"", "") + "\"}", null);
            return Map.of("id", id);
        }

        @PostMapping("/expenses/{id}/void")
        @PreAuthorize("hasAuthority('PERM_branch_expenses_edit')")
        Map<String, Object> voidIt(@PathVariable long id, @Valid @RequestBody VoidReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            svc.voidIt(id, me.branchId(), me.userId(), r.reason());
            audit.record(who.getName(), me.branchId(), "EXPENSE_VOID", "expense", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }
    }

    @RestController
    @RequestMapping("/api/admin")
    static class AdminApi {
        private final Svc svc;
        private final AuditService audit;

        AdminApi(Svc svc, AuditService audit) { this.svc = svc; this.audit = audit; }

        @GetMapping("/expense-categories")
        @PreAuthorize("hasAuthority('PERM_accounting_view')")
        List<Category> categories() { return svc.categories(false); }

        @PostMapping("/expense-categories")
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_accounting_add')")
        Map<String, Object> addCategory(@Valid @RequestBody CatReq r, Principal who) {
            long id = svc.addCategory(r.name().trim());
            audit.record(who.getName(), null, "EXPENSE_CATEGORY_CREATE", "expense_category", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PostMapping("/expense-categories/{id}")
        @PreAuthorize("hasAuthority('PERM_accounting_edit')")
        Map<String, Object> editCategory(@PathVariable long id, @Valid @RequestBody CatReq r, Principal who) {
            svc.editCategory(id, r.name().trim(), r.active());
            audit.record(who.getName(), null, "EXPENSE_CATEGORY_UPDATE", "expense_category", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @GetMapping("/expenses")
        @PreAuthorize("hasAuthority('PERM_accounting_view')")
        List<Expense> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) Long branchId) {
            return svc.list(branchId, ReportService.range(from, to), true);
        }

        /** HQ records an expense for a branch, or for itself when branchId is blank. */
        @PostMapping("/expenses")
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_accounting_add')")
        Map<String, Object> create(@Valid @RequestBody CreateReq r, Principal who) {
            long id = svc.create(r.branchId(), svc.userId(who.getName()), r);
            audit.record(who.getName(), r.branchId(), "EXPENSE_CREATE", "expense", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PostMapping("/expenses/{id}/void")
        @PreAuthorize("hasAuthority('PERM_accounting_edit')")
        Map<String, Object> voidIt(@PathVariable long id, @Valid @RequestBody VoidReq r, Principal who) {
            svc.voidIt(id, null, svc.userId(who.getName()), r.reason());
            audit.record(who.getName(), null, "EXPENSE_VOID", "expense", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }
    }
}
