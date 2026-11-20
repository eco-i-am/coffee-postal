package ph.coffeepostal.pos.inventory;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.coffeepostal.pos.shared.ApiException;

/** HQ item master list and each branch's stock ledger. */
@Service
public class InventoryService {
    public record ItemRow(long id, String name, String unit, long cost, long reorderLevel, boolean active, Long categoryId, String category, boolean orderable,
                          String sku, String barcode, String description) {}
    public record HqMovement(long id, String branch, long qty, String kind, String reason, String ref, String by, Instant at) {}
    public record CostChange(Instant at, String by, String action, Long oldCost, Long newCost) {}
    public record StockRow(long itemId, String name, String unit, long cost, long onHand, long reorderLevel, boolean low, String category, boolean orderable) {}
    public record Movement(long id, long qty, String kind, String reason, String ref, String by, Instant at) {}

    /** Stock is counted in thousandths of an item's unit: 1 bag = 1000. */
    public static final long SCALE = 1000;

    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ItemRow> items() {
        return jdbc.query("""
            SELECT i.id, i.name, i.unit, i.cost, i.reorder_level, i.active, i.category_id, c.name AS category, i.orderable, i.sku, i.barcode, i.description
            FROM items i LEFT JOIN item_categories c ON c.id = i.category_id ORDER BY lower(i.name)
            """, (rs, i) -> new ItemRow(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getLong("cost"),
                rs.getLong("reorder_level"), rs.getBoolean("active"), (Long) rs.getObject("category_id"), rs.getString("category"), rs.getBoolean("orderable"),
                rs.getString("sku"), rs.getString("barcode"), rs.getString("description")));
    }

    /** SKU, barcode and description (blank means none). */
    @Transactional
    public void setItemExtras(long id, String sku, String barcode, String description) {
        jdbc.update("UPDATE items SET sku = ?, barcode = ?, description = ? WHERE id = ?", blank(sku), blank(barcode), blank(description), id);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public boolean itemSkuTaken(String sku, long exceptId) {
        if (blank(sku) == null) return false;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM items WHERE lower(sku) = lower(?) AND id <> ?", Integer.class, sku.trim(), exceptId);
        return n != null && n > 0;
    }

    public boolean itemBarcodeTaken(String barcode, long exceptId) {
        if (blank(barcode) == null) return false;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM items WHERE lower(barcode) = lower(?) AND id <> ?", Integer.class, barcode.trim(), exceptId);
        return n != null && n > 0;
    }

    /** A copy of an item: same unit, cost, low-stock level, category, other units, description and photo. SKU and barcode are not copied. */
    @Transactional
    public java.util.Map<String, Object> duplicateItem(long id) {
        List<String> found = jdbc.query("SELECT name FROM items WHERE id = ?", (rs, i) -> rs.getString(1), id);
        if (found.isEmpty()) return null;
        String name = found.get(0) + " (copy)";
        for (int n = 2; itemNameExists(name, null); n++) name = found.get(0) + " (copy " + n + ")";
        long copy = jdbc.queryForObject("""
            INSERT INTO items(name, unit, cost, reorder_level, category_id, description)
            SELECT ?, unit, cost, reorder_level, category_id, description FROM items WHERE id = ? RETURNING id
            """, Long.class, name, id);
        jdbc.update("INSERT INTO item_conversions(item_id, unit, factor) SELECT ?, unit, factor FROM item_conversions WHERE item_id = ?", copy, id);
        jdbc.update("""
            INSERT INTO item_images(owner_type, owner_id, content_type, data, updated_by)
            SELECT 'ITEM', ?, content_type, data, updated_by FROM item_images WHERE owner_type = 'ITEM' AND owner_id = ?
            """, copy, id);
        return java.util.Map.of("id", copy, "name", name);
    }

    /** Stock changes for one item in every branch, newest first (HQ view). */
    public List<HqMovement> allMovements(long itemId) {
        return jdbc.query("""
            SELECT m.id, b.name, m.qty, m.kind, m.reason, m.ref, coalesce(u.full_name, ''), m.created_at
            FROM stock_movements m JOIN branches b ON b.id = m.branch_id LEFT JOIN users u ON u.id = m.user_id
            WHERE m.item_id = ? ORDER BY m.created_at DESC, m.id DESC LIMIT 200
            """, (rs, i) -> new HqMovement(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getTimestamp(8).toInstant()), itemId);
    }

    /** When an item was created and every time its cost changed, from the audit log. Newest first. */
    public List<CostChange> costHistory(long itemId) {
        return jdbc.query("""
            SELECT a.at, coalesce(u.full_name, ''), a.action, (a.before_json->>'cost')::bigint, (a.after_json->>'cost')::bigint
            FROM audit_logs a LEFT JOIN users u ON u.id = a.user_id
            WHERE a.entity = 'item' AND a.entity_id = ? AND a.action IN ('ITEM_CREATE', 'ITEM_COST_CHANGE')
            ORDER BY a.at DESC, a.id DESC LIMIT 100
            """, (rs, i) -> new CostChange(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), (Long) rs.getObject(5)),
            String.valueOf(itemId));
    }

    public boolean itemNameExists(String name, Long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM items WHERE lower(name) = lower(?) AND id <> ?", Integer.class,
            name, exceptId == null ? -1L : exceptId);
        return n != null && n > 0;
    }

    @Transactional
    public long createItem(String name, String unit, long cost, long reorderLevel, Long categoryId) {
        checkCategory(categoryId);
        registerUnit(unit);
        return jdbc.queryForObject("INSERT INTO items(name, unit, cost, reorder_level, category_id) VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class, name, unit, cost, reorderLevel, categoryId);
    }

    private void checkCategory(Long categoryId) {
        if (categoryId == null) return;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM item_categories WHERE id = ? AND active", Integer.class, categoryId);
        if (n == null || n == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a category from the list.");
    }

    /** A unit typed on an item joins the units list, so conversions and reports share one spelling. */
    private void registerUnit(String unit) {
        jdbc.update("INSERT INTO units(code) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM units WHERE lower(code) = lower(?))", unit, unit);
    }

    /** A quantity typed in another unit, as the item's own unit in thousandths. Null/blank/own unit means qty is already in the item's unit. */
    public long toBase(long itemId, long qty, String unit) {
        if (unit == null || unit.isBlank()) return qty;
        var row = jdbc.query("SELECT unit FROM items WHERE id = ?", (rs, i) -> rs.getString(1), itemId).stream().findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That item no longer exists."));
        if (row.equalsIgnoreCase(unit.trim())) return qty;
        Long f = jdbc.query("SELECT factor FROM item_conversions WHERE item_id = ? AND lower(unit) = lower(?)", (rs, i) -> rs.getLong(1), itemId, unit.trim())
            .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "This item has no conversion for " + unit.trim() + "."));
        return java.math.BigDecimal.valueOf(qty).multiply(java.math.BigDecimal.valueOf(f)).divide(java.math.BigDecimal.valueOf(SCALE), 0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    @Transactional
    public boolean updateItem(long id, String unit, long cost, long reorderLevel, Long categoryId) {
        checkCategory(categoryId);
        registerUnit(unit);
        return jdbc.update("UPDATE items SET unit = ?, cost = ?, reorder_level = ?, category_id = ? WHERE id = ?", unit, cost, reorderLevel, categoryId, id) == 1;
    }

    /** Returns the previous value, or null if the item does not exist. */
    @Transactional
    public Boolean setOrderable(long id, boolean on) {
        List<Boolean> was = jdbc.query("SELECT orderable FROM items WHERE id = ? FOR UPDATE", (rs, i) -> rs.getBoolean(1), id);
        if (was.isEmpty()) return null;
        jdbc.update("UPDATE items SET orderable = ? WHERE id = ?", on, id);
        return was.get(0);
    }

    /** Returns the new value of active, or null if the item does not exist. */
    @Transactional
    public Boolean toggleItem(long id) {
        List<Boolean> r = jdbc.query("UPDATE items SET active = NOT active WHERE id = ? RETURNING active", (rs, i) -> rs.getBoolean(1), id);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Every active item with what this branch has on hand. */
    public List<StockRow> stock(long branchId) {
        return jdbc.query("""
            SELECT i.id, i.name, i.unit, i.cost, i.reorder_level,
                   coalesce((SELECT sum(m.qty) FROM stock_movements m WHERE m.branch_id = ? AND m.item_id = i.id), 0) AS on_hand,
                   c.name AS category, i.orderable
            FROM items i LEFT JOIN item_categories c ON c.id = i.category_id WHERE i.active ORDER BY lower(i.name)
            """, (rs, i) -> {
                long onHand = rs.getLong("on_hand"), reorder = rs.getLong("reorder_level");
                return new StockRow(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getLong("cost"),
                    onHand, reorder, onHand < 0 || (reorder > 0 && onHand <= reorder * SCALE), rs.getString("category"), rs.getBoolean("orderable"));
            }, branchId);
    }

    public long onHand(long branchId, long itemId) {
        Long n = jdbc.queryForObject("SELECT coalesce(sum(qty), 0) FROM stock_movements WHERE branch_id = ? AND item_id = ?",
            Long.class, branchId, itemId);
        return n == null ? 0 : n;
    }

    /** Appends to the ledger. Refuses to take a branch below zero. Serialised per branch+item so two cashiers cannot both win. */
    @Transactional
    public void move(long branchId, long itemId, long qty, String kind, String reason, String ref, Long userId) {
        jdbc.query("SELECT id FROM items WHERE id = ? FOR UPDATE", rs -> { }, itemId);
        if (qty < 0 && onHand(branchId, itemId) + qty < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Only " + show(onHand(branchId, itemId)) + " on hand. You cannot take out " + show(-qty) + ".");
        }
        jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason, ref, user_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
            branchId, itemId, qty, kind, reason, ref, userId);
    }

    /** 1500 -> "1.5". */
    public static String show(long thousandths) {
        return java.math.BigDecimal.valueOf(thousandths, 3).stripTrailingZeros().toPlainString();
    }

    // ---- recipes ----
    public record RecipeLine(long itemId, String name, String unit, long qty) {}

    public List<RecipeLine> recipe(long productId) {
        return jdbc.query("""
            SELECT r.item_id, i.name, i.unit, r.qty FROM product_recipes r JOIN items i ON i.id = r.item_id
            WHERE r.product_id = ? ORDER BY lower(i.name)
            """, (rs, i) -> new RecipeLine(rs.getLong("item_id"), rs.getString("name"), rs.getString("unit"), rs.getLong("qty")), productId);
    }

    public boolean productExists(long productId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?", Integer.class, productId);
        return n != null && n > 0;
    }

    /** Replaces the whole recipe. qty is in thousandths per one product sold. */
    @Transactional
    public void saveRecipe(long productId, java.util.Map<Long, Long> qtyByItem) {
        jdbc.update("DELETE FROM product_recipes WHERE product_id = ?", productId);
        for (var e : qtyByItem.entrySet()) {
            int n = jdbc.update("INSERT INTO product_recipes(product_id, item_id, qty) SELECT ?, i.id, ? FROM items i WHERE i.id = ?",
                productId, e.getValue(), e.getKey());
            if (n == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "An item in this recipe no longer exists.");
        }
    }

    public boolean itemActive(long itemId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM items WHERE id = ? AND active", Integer.class, itemId);
        return n != null && n > 0;
    }

    public List<Movement> movements(long branchId, long itemId) {
        return jdbc.query("""
            SELECT m.id, m.qty, m.kind, m.reason, m.ref, coalesce(u.full_name, '') AS who, m.created_at
            FROM stock_movements m LEFT JOIN users u ON u.id = m.user_id
            WHERE m.branch_id = ? AND m.item_id = ? ORDER BY m.created_at DESC, m.id DESC LIMIT 40
            """, (rs, i) -> new Movement(rs.getLong("id"), rs.getLong("qty"), rs.getString("kind"), rs.getString("reason"),
                rs.getString("ref"), rs.getString("who"), rs.getTimestamp("created_at").toInstant()), branchId, itemId);
    }
}
