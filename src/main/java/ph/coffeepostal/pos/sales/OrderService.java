package ph.coffeepostal.pos.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Places POS orders. The server prices everything from the database (the client only says WHAT was sold),
 * applies the discount and VAT rules, and is idempotent on the client-generated order id.
 * Money is integer centavos; prices include 12% VAT.
 */
@Service
public class OrderService {
    static final Set<String> TYPES = Set.of("DINE_IN", "TAKEOUT", "DELIVERY");
    static final Set<String> DISCOUNTS = Set.of("NONE", "SENIOR_PWD", "PROMO10");
    static final Set<String> METHODS = Set.of("CASH", "GCASH", "MAYA", "CARD");
    private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.12");

    public record Who(long userId, long branchId) {}
    public record Totals(long subtotal, long discount, long total, long vatable, long vat) {}

    private final JdbcTemplate jdbc;

    public OrderService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Who whoIs(String username) {
        List<Who> r = jdbc.query("""
            SELECT u.id AS uid, ub.branch_id FROM users u JOIN user_branches ub ON ub.user_id = u.id
            WHERE u.username = ? ORDER BY ub.branch_id LIMIT 1
            """, (rs, i) -> new Who(rs.getLong("uid"), rs.getLong("branch_id")), username);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account is not assigned to a branch.");
        return r.get(0);
    }

    /** Discount and VAT rules. Senior/PWD: VAT-exempt, then 20% off the VAT-exclusive price. */
    public static Totals compute(long gross, String discountType) {
        long discount, total, vatable, vat;
        switch (discountType) {
            case "SENIOR_PWD" -> {
                long net = divVat(gross);
                discount = Math.round(net * 0.20) + (gross - net);
                total = gross - discount;
                vatable = 0; vat = 0;
            }
            case "PROMO10" -> {
                discount = Math.round(gross * 0.10);
                total = gross - discount;
                vatable = divVat(total); vat = total - vatable;
            }
            default -> {
                discount = 0; total = gross;
                vatable = divVat(total); vat = total - vatable;
            }
        }
        return new Totals(gross, discount, total, vatable, vat);
    }

    private static long divVat(long v) {
        return BigDecimal.valueOf(v).divide(VAT_DIVISOR, 0, RoundingMode.HALF_UP).longValueExact();
    }

    private record Priced(long productId, String name, String variant, String addons, long unit, int qty) {}

    @Transactional
    public OrderRes place(String username, OrderReq r) {
        Who who = whoIs(username);

        // retry of an order we already saved: return the same answer, do not charge twice
        List<OrderRes> existing = jdbc.query("""
            SELECT o.order_no, o.subtotal, o.discount, o.total, o.vatable, o.vat, p.tendered - p.amount AS change
            FROM orders o JOIN order_payments p ON p.order_id = o.id WHERE o.id = ? AND o.branch_id = ?
            """, (rs, i) -> new OrderRes(rs.getLong("order_no"), rs.getLong("subtotal"), rs.getLong("discount"),
                rs.getLong("total"), rs.getLong("vatable"), rs.getLong("vat"), rs.getLong("change")), r.id(), who.branchId());
        if (!existing.isEmpty()) return existing.get(0);

        if (!TYPES.contains(r.orderType())) throw bad("Pick dine-in, takeout or delivery.");
        if (!DISCOUNTS.contains(r.discountType())) throw bad("Unknown discount.");
        if (!METHODS.contains(r.paymentMethod())) throw bad("Unknown payment method.");

        List<Priced> lines = new ArrayList<>();
        long gross = 0;
        for (OrderLineReq l : r.lines()) {
            Priced p = price(who.branchId(), l);
            lines.add(p);
            gross = Math.addExact(gross, Math.multiplyExact(p.unit(), (long) p.qty()));
        }
        Totals t = compute(gross, r.discountType());

        long tendered = t.total();
        if ("CASH".equals(r.paymentMethod())) {
            if (r.tendered() == null || r.tendered() < t.total()) throw bad("Cash received is less than the total.");
            tendered = r.tendered();
        }

        long orderNo = jdbc.queryForObject("""
            INSERT INTO branch_order_counters(branch_id, last_no) VALUES (?, 1)
            ON CONFLICT (branch_id) DO UPDATE SET last_no = branch_order_counters.last_no + 1 RETURNING last_no
            """, Long.class, who.branchId());
        jdbc.update("""
            INSERT INTO orders(id, branch_id, order_no, cashier_id, order_type, discount_type, subtotal, discount, total, vatable, vat)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, r.id(), who.branchId(), orderNo, who.userId(), r.orderType(), r.discountType(),
            t.subtotal(), t.discount(), t.total(), t.vatable(), t.vat());
        for (Priced p : lines) {
            jdbc.update("INSERT INTO order_lines(order_id, product_id, name, variant, addons, unit_price, qty) VALUES (?, ?, ?, ?, ?, ?, ?)",
                r.id(), p.productId(), p.name(), p.variant(), p.addons(), p.unit(), p.qty());
        }
        jdbc.update("INSERT INTO order_payments(order_id, method, amount, tendered, reference) VALUES (?, ?, ?, ?, ?)",
            r.id(), r.paymentMethod(), t.total(), tendered, r.reference());
        return new OrderRes(orderNo, t.subtotal(), t.discount(), t.total(), t.vatable(), t.vat(), tendered - t.total());
    }

    private Priced price(long branchId, OrderLineReq l) {
        var prod = jdbc.query("""
            SELECT p.name, coalesce(bp.price, p.price) AS price FROM products p
            JOIN product_categories c ON c.id = p.category_id AND c.active
            LEFT JOIN branch_products bp ON bp.product_id = p.id AND bp.branch_id = ?
            WHERE p.id = ? AND p.active AND coalesce(bp.available, TRUE)
            """, (rs, i) -> new Object[] { rs.getString("name"), rs.getLong("price") }, branchId, l.productId());
        if (prod.isEmpty()) throw bad("An item on this order is no longer on the menu. Refresh the screen.");
        String name = (String) prod.get(0)[0];
        long unit = (Long) prod.get(0)[1];

        String variant = null;
        if (l.variantId() != null) {
            var v = jdbc.query("SELECT name, price_delta FROM product_variants WHERE id = ? AND product_id = ?",
                (rs, i) -> new Object[] { rs.getString("name"), rs.getLong("price_delta") }, l.variantId(), l.productId());
            if (v.isEmpty()) throw bad("Unknown size for " + name + ".");
            variant = (String) v.get(0)[0];
            unit += (Long) v.get(0)[1];
        }
        List<String> addonNames = new ArrayList<>();
        if (l.addonIds() != null) {
            for (Long aid : l.addonIds().stream().distinct().toList()) {
                var a = jdbc.query("""
                    SELECT a.name, a.price FROM addons a JOIN product_addons pa ON pa.addon_id = a.id
                    WHERE a.id = ? AND pa.product_id = ? AND a.active
                    """, (rs, i) -> new Object[] { rs.getString("name"), rs.getLong("price") }, aid, l.productId());
                if (a.isEmpty()) throw bad("Unknown add-on for " + name + ".");
                addonNames.add((String) a.get(0)[0]);
                unit += (Long) a.get(0)[1];
            }
        }
        return new Priced(l.productId(), name, variant, addonNames.isEmpty() ? null : String.join(", ", addonNames), unit, l.qty());
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }

    public static UUID newId() { return UUID.randomUUID(); }
}
