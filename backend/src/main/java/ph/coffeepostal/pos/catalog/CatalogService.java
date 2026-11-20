package ph.coffeepostal.pos.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Products, categories and the menu the POS shows. All prices are integer centavos. */
@Service
public class CatalogService {
    public record Category(long id, String name) {}
    public record ProductRow(long id, String name, String category, long categoryId, long price, long cost, boolean hasSizes, boolean active, boolean published, String art,
                             String sku, String barcode, String description) {}
    public record CategoryRow(long id, String name, Long headerId, String header, int sortOrder, boolean active) {}
    public record Header(long id, String name, int sortOrder, boolean active) {}
    public record Opt(long id, String name, long price) {}
    public record MenuItem(long id, String name, String category, String header, long price, String art, String color,
                           List<Opt> sizes, List<Opt> addons) {}
    public record DiscountOpt(String code, String name, String kind, long value, boolean requiresId, Long approvalOver) {}
    public record Menu(List<String> categories, List<MenuItem> items, List<DiscountOpt> discounts, boolean vatRegistered) {}

    /** Extra charge for the Large size on new drinks (centavos). */
    static final long LARGE_EXTRA = 2000;

    private final JdbcTemplate jdbc;

    public CatalogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Category> categories() {
        return jdbc.query("SELECT id, name FROM product_categories WHERE active ORDER BY sort_order, name",
            (rs, i) -> new Category(rs.getLong("id"), rs.getString("name")));
    }

    public List<ProductRow> adminProducts() {
        return jdbc.query("""
            SELECT p.id, p.name, c.name AS category, p.category_id, p.price, p.cost, p.has_sizes, p.active, p.published, p.art,
                   p.sku, p.barcode, p.description
            FROM products p JOIN product_categories c ON c.id = p.category_id
            ORDER BY c.sort_order, c.name, p.name
            """, (rs, i) -> new ProductRow(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
                rs.getLong("category_id"), rs.getLong("price"), rs.getLong("cost"), rs.getBoolean("has_sizes"),
                rs.getBoolean("active"), rs.getBoolean("published"), rs.getString("art"),
                rs.getString("sku"), rs.getString("barcode"), rs.getString("description")));
    }

    public List<CategoryRow> adminCategories() {
        return jdbc.query("""
            SELECT c.id, c.name, c.header_id, h.name AS header, c.sort_order, c.active
            FROM product_categories c LEFT JOIN product_headers h ON h.id = c.header_id
            ORDER BY c.sort_order, c.name
            """, (rs, i) -> new CategoryRow(rs.getLong("id"), rs.getString("name"), (Long) rs.getObject("header_id"),
                rs.getString("header"), rs.getInt("sort_order"), rs.getBoolean("active")));
    }

    public List<Header> headers() {
        return jdbc.query("SELECT id, name, sort_order, active FROM product_headers ORDER BY sort_order, name",
            (rs, i) -> new Header(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)));
    }

    @Transactional
    public long createHeader(String name) {
        return jdbc.queryForObject("""
            INSERT INTO product_headers(name, sort_order)
            VALUES (?, (SELECT coalesce(max(sort_order), 0) + 1 FROM product_headers)) RETURNING id
            """, Long.class, name);
    }

    public boolean headerNameTaken(String name, long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM product_headers WHERE lower(name) = lower(?) AND id <> ?", Integer.class, name, exceptId);
        return n != null && n > 0;
    }

    /** Returns false if the header does not exist. */
    @Transactional
    public boolean updateHeader(long id, String name, int sortOrder, boolean active) {
        return jdbc.update("UPDATE product_headers SET name = ?, sort_order = ?, active = ? WHERE id = ?", name, sortOrder, active, id) > 0;
    }

    public boolean categoryNameTaken(String name, long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM product_categories WHERE lower(name) = lower(?) AND id <> ?", Integer.class, name, exceptId);
        return n != null && n > 0;
    }

    @Transactional
    public boolean updateCategory(long id, String name, Long headerId, int sortOrder, boolean active) {
        return jdbc.update("UPDATE product_categories SET name = ?, header_id = ?, sort_order = ?, active = ? WHERE id = ?",
            name, headerId, sortOrder, active, id) > 0;
    }

    public ProductRow product(long id) {
        return adminProducts().stream().filter(p -> p.id() == id).findFirst().orElse(null);
    }

    public boolean productNameTaken(String name, long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM products WHERE lower(name) = lower(?) AND id <> ?", Integer.class, name, exceptId);
        return n != null && n > 0;
    }

    /** Edits a product. Switching sizes on creates Regular/Large if the product has none yet. */
    @Transactional
    public boolean updateProduct(long id, long categoryId, String name, long price, long cost, boolean hasSizes, String art, String color, boolean published) {
        int n = jdbc.update("""
            UPDATE products SET category_id = ?, name = ?, price = ?, cost = ?, has_sizes = ?, art = ?, color = ?,
                   published = ?, updated_at = now() WHERE id = ?
            """, categoryId, name, price, cost, hasSizes, art, color, published, id);
        if (n == 0) return false;
        if (hasSizes) {
            jdbc.update("""
                INSERT INTO product_variants(product_id, name, price_delta, sort_order)
                SELECT ?, 'Regular', 0, 0 WHERE NOT EXISTS (SELECT 1 FROM product_variants WHERE product_id = ?)
                """, id, id);
            jdbc.update("""
                INSERT INTO product_variants(product_id, name, price_delta, sort_order)
                SELECT ?, 'Large', ?, 1 WHERE NOT EXISTS (SELECT 1 FROM product_variants WHERE product_id = ? AND name = 'Large')
                """, id, LARGE_EXTRA, id);
        }
        return true;
    }

    @Transactional
    public long createCategory(String name) {
        return jdbc.queryForObject("""
            INSERT INTO product_categories(name, sort_order)
            VALUES (?, (SELECT coalesce(max(sort_order), 0) + 1 FROM product_categories)) RETURNING id
            """, Long.class, name);
    }

    public boolean categoryExists(String name) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM product_categories WHERE lower(name) = lower(?)", Integer.class, name);
        return n != null && n > 0;
    }

    @Transactional
    public long createProduct(long categoryId, String name, long price, long cost, boolean hasSizes, String art, String color) {
        long id = jdbc.queryForObject("""
            INSERT INTO products(category_id, name, price, cost, has_sizes, art, color)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class, categoryId, name, price, cost, hasSizes, art, color);
        if (hasSizes) {
            jdbc.update("INSERT INTO product_variants(product_id, name, price_delta, sort_order) VALUES (?, 'Regular', 0, 0), (?, 'Large', ?, 1)",
                id, id, LARGE_EXTRA);
            // new drinks get every active add-on by default; HQ can narrow this later
            jdbc.update("INSERT INTO product_addons(product_id, addon_id) SELECT ?, id FROM addons WHERE active", id);
        }
        return id;
    }

    /** SKU, barcode and description (blank means none). Call inside the same request as the create or edit. */
    @Transactional
    public void setProductExtras(long id, String sku, String barcode, String description) {
        jdbc.update("UPDATE products SET sku = ?, barcode = ?, description = ? WHERE id = ?", blank(sku), blank(barcode), blank(description), id);
    }

    public static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public boolean productSkuTaken(String sku, long exceptId) {
        if (blank(sku) == null) return false;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM products WHERE lower(sku) = lower(?) AND id <> ?", Integer.class, sku.trim(), exceptId);
        return n != null && n > 0;
    }

    public boolean productBarcodeTaken(String barcode, long exceptId) {
        if (blank(barcode) == null) return false;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM products WHERE lower(barcode) = lower(?) AND id <> ?", Integer.class, barcode.trim(), exceptId);
        return n != null && n > 0;
    }

    /**
     * A copy of a product as a draft (not on the menu until published): same category, price, cost, sizes, add-ons, recipe, description
     * and photo. The SKU and barcode are not copied, because each must stay unique. Returns the new id and its name.
     */
    @Transactional
    public Map<String, Object> duplicateProduct(long id) {
        ProductRow p = product(id);
        if (p == null) return null;
        String name = p.name() + " (copy)";
        for (int n = 2; productNameExists(name); n++) name = p.name() + " (copy " + n + ")";
        long copy = jdbc.queryForObject("""
            INSERT INTO products(category_id, name, price, cost, has_sizes, art, color, description, published)
            SELECT category_id, ?, price, cost, has_sizes, art, color, description, FALSE FROM products WHERE id = ? RETURNING id
            """, Long.class, name, id);
        jdbc.update("INSERT INTO product_variants(product_id, name, price_delta, sort_order) SELECT ?, name, price_delta, sort_order FROM product_variants WHERE product_id = ?", copy, id);
        jdbc.update("INSERT INTO product_addons(product_id, addon_id) SELECT ?, addon_id FROM product_addons WHERE product_id = ?", copy, id);
        jdbc.update("INSERT INTO product_recipes(product_id, item_id, qty) SELECT ?, item_id, qty FROM product_recipes WHERE product_id = ?", copy, id);
        jdbc.update("""
            INSERT INTO item_images(owner_type, owner_id, content_type, data, updated_by)
            SELECT 'PRODUCT', ?, content_type, data, updated_by FROM item_images WHERE owner_type = 'PRODUCT' AND owner_id = ?
            """, copy, id);
        return Map.of("id", copy, "name", name);
    }

    public record PriceChange(java.time.Instant at, String by, String action, Long oldPrice, Long newPrice, Long oldCost, Long newCost) {}

    /** When a product was created and every time its price or cost changed, from the audit log. Newest first. */
    public List<PriceChange> priceHistory(long id) {
        return jdbc.query("""
            SELECT a.at, coalesce(u.full_name, ''), a.action,
                   (a.before_json->>'price')::bigint, (a.after_json->>'price')::bigint, (a.before_json->>'cost')::bigint, (a.after_json->>'cost')::bigint
            FROM audit_logs a LEFT JOIN users u ON u.id = a.user_id
            WHERE a.entity = 'product' AND a.entity_id = ? AND a.action IN ('PRODUCT_CREATE', 'PRODUCT_PRICE_CHANGE')
            ORDER BY a.at DESC, a.id DESC LIMIT 100
            """, (rs, i) -> new PriceChange(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3),
                (Long) rs.getObject(4), (Long) rs.getObject(5), (Long) rs.getObject(6), (Long) rs.getObject(7)), String.valueOf(id));
    }

    public boolean productNameExists(String name) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM products WHERE lower(name) = lower(?)", Integer.class, name);
        return n != null && n > 0;
    }

    /** Returns the new value of active, or null if the product does not exist. */
    @Transactional
    public Boolean toggleProduct(long id) {
        List<Boolean> r = jdbc.query("UPDATE products SET active = NOT active, updated_at = now() WHERE id = ? RETURNING active",
            (rs, i) -> rs.getBoolean(1), id);
        return r.isEmpty() ? null : r.get(0);
    }

    /** The menu for one branch: active products, minus anything that branch has switched off, at the branch price if set. */
    public Menu menu(long branchId) {
        Map<Long, List<Opt>> sizes = new HashMap<>();
        jdbc.query("SELECT id, product_id, name, price_delta FROM product_variants ORDER BY sort_order, id", rs -> {
            sizes.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>())
                .add(new Opt(rs.getLong("id"), rs.getString("name"), rs.getLong("price_delta")));
        });
        Map<Long, List<Opt>> addons = new HashMap<>();
        jdbc.query("""
            SELECT pa.product_id, a.id, a.name, a.price FROM product_addons pa
            JOIN addons a ON a.id = pa.addon_id WHERE a.active ORDER BY a.name
            """, rs -> {
            addons.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>())
                .add(new Opt(rs.getLong("id"), rs.getString("name"), rs.getLong("price")));
        });
        LinkedHashSet<String> cats = new LinkedHashSet<>();
        List<MenuItem> items = jdbc.query("""
            SELECT p.id, p.name, c.name AS category, h.name AS header, coalesce(bp.price, p.price) AS price, p.art, p.color
            FROM products p
            JOIN product_categories c ON c.id = p.category_id AND c.active
            LEFT JOIN product_headers h ON h.id = c.header_id
            LEFT JOIN branch_products bp ON bp.product_id = p.id AND bp.branch_id = ?
            WHERE p.active AND p.published AND coalesce(bp.available, TRUE) AND (h.id IS NULL OR h.active)
            ORDER BY coalesce(h.sort_order, 2147483647), c.sort_order, c.name, p.name
            """, (rs, i) -> {
                long id = rs.getLong("id");
                cats.add(rs.getString("category"));
                return new MenuItem(id, rs.getString("name"), rs.getString("category"), rs.getString("header"), rs.getLong("price"),
                    rs.getString("art"), rs.getString("color"),
                    sizes.getOrDefault(id, List.of()), addons.getOrDefault(id, List.of()));
            }, branchId);
        List<DiscountOpt> discounts = jdbc.query("SELECT code, name, kind, value, requires_id, approval_over FROM discounts WHERE active ORDER BY sort_order, name",
            (rs, i) -> new DiscountOpt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getBoolean(5), (Long) rs.getObject(6)));
        boolean vat = Boolean.TRUE.equals(jdbc.queryForObject("SELECT vat_registered FROM branches WHERE id = ?", Boolean.class, branchId));
        return new Menu(new ArrayList<>(cats), items, discounts, vat);
    }
}
