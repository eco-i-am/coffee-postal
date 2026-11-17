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

        // An order that waited offline carries the time it was really sold. Accept up to 7 days back, never the future.
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant soldAt = now;
        if (r.soldAt() != null) {
            if (r.soldAt().isAfter(now.plusSeconds(300))) throw bad("The sale time is in the future. Check the clock on this device.");
            if (r.soldAt().isBefore(now.minus(java.time.Duration.ofDays(7)))) throw bad("This sale is more than 7 days old and cannot be uploaded. Ask HQ to enter it.");
            soldAt = r.soldAt().isAfter(now) ? now : r.soldAt();
        }
        java.sql.Timestamp soldTs = java.sql.Timestamp.from(soldAt);

        long orderNo = jdbc.queryForObject("""
            INSERT INTO branch_order_counters(branch_id, last_no) VALUES (?, 1)
            ON CONFLICT (branch_id) DO UPDATE SET last_no = branch_order_counters.last_no + 1 RETURNING last_no
            """, Long.class, who.branchId());
        // tie the sale to the cashier's open shift, if they have one (so the drawer count can add up)
        // (the shift that was open when it was SOLD, which matters for an order uploaded after the shift closed)
        List<Long> open = jdbc.queryForList("""
            SELECT id FROM shifts WHERE cashier_id = ? AND opened_at <= ? AND (closed_at IS NULL OR closed_at >= ?)
            ORDER BY opened_at DESC LIMIT 1
            """, Long.class, who.userId(), soldTs, soldTs);
        jdbc.update("""
            INSERT INTO orders(id, branch_id, order_no, cashier_id, order_type, discount_type, subtotal, discount, total, vatable, vat, shift_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, r.id(), who.branchId(), orderNo, who.userId(), r.orderType(), r.discountType(),
            t.subtotal(), t.discount(), t.total(), t.vatable(), t.vat(), open.isEmpty() ? null : open.get(0), soldTs);
        for (Priced p : lines) {
            jdbc.update("INSERT INTO order_lines(order_id, product_id, name, variant, addons, unit_price, qty) VALUES (?, ?, ?, ?, ?, ?, ?)",
                r.id(), p.productId(), p.name(), p.variant(), p.addons(), p.unit(), p.qty());
        }
        // Use up stock according to each product's recipe. A sale is never blocked by low stock
        // (the drink is already made); the branch just goes negative and shows as "low".
        jdbc.update("""
            INSERT INTO stock_movements(branch_id, item_id, qty, kind, ref, user_id, created_at)
            SELECT ?, r.item_id, -sum(r.qty * ol.qty), 'SALE', ?, ?, ?
            FROM order_lines ol JOIN product_recipes r ON r.product_id = ol.product_id
            WHERE ol.order_id = ? GROUP BY r.item_id HAVING sum(r.qty * ol.qty) > 0
            """, who.branchId(), "ORD-" + orderNo, who.userId(), soldTs, r.id());
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
