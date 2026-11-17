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
    public record ProductRow(long id, String name, String category, long price, boolean hasSizes, boolean active) {}
    public record Opt(long id, String name, long price) {}
    public record MenuItem(long id, String name, String category, long price, String art, String color,
                           List<Opt> sizes, List<Opt> addons) {}
    public record Menu(List<String> categories, List<MenuItem> items) {}

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
            SELECT p.id, p.name, c.name AS category, p.price, p.has_sizes, p.active
            FROM products p JOIN product_categories c ON c.id = p.category_id
            ORDER BY c.sort_order, c.name, p.name
            """, (rs, i) -> new ProductRow(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
                rs.getLong("price"), rs.getBoolean("has_sizes"), rs.getBoolean("active")));
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
            SELECT p.id, p.name, c.name AS category, coalesce(bp.price, p.price) AS price, p.art, p.color
            FROM products p
            JOIN product_categories c ON c.id = p.category_id AND c.active
            LEFT JOIN branch_products bp ON bp.product_id = p.id AND bp.branch_id = ?
            WHERE p.active AND coalesce(bp.available, TRUE)
            ORDER BY c.sort_order, c.name, p.name
            """, (rs, i) -> {
                long id = rs.getLong("id");
                cats.add(rs.getString("category"));
                return new MenuItem(id, rs.getString("name"), rs.getString("category"), rs.getLong("price"),
                    rs.getString("art"), rs.getString("color"),
                    sizes.getOrDefault(id, List.of()), addons.getOrDefault(id, List.of()));
            }, branchId);
        return new Menu(new ArrayList<>(cats), items);
    }
}
