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
    public record ItemRow(long id, String name, String unit, long cost, long reorderLevel, boolean active) {}
    public record StockRow(long itemId, String name, String unit, long cost, long onHand, long reorderLevel, boolean low) {}
    public record Movement(long id, long qty, String kind, String reason, String ref, String by, Instant at) {}

    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ItemRow> items() {
        return jdbc.query("SELECT id, name, unit, cost, reorder_level, active FROM items ORDER BY lower(name)",
            (rs, i) -> new ItemRow(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getLong("cost"),
                rs.getLong("reorder_level"), rs.getBoolean("active")));
    }

    public boolean itemNameExists(String name, Long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM items WHERE lower(name) = lower(?) AND id <> ?", Integer.class,
            name, exceptId == null ? -1L : exceptId);
        return n != null && n > 0;
    }

    @Transactional
    public long createItem(String name, String unit, long cost, long reorderLevel) {
        return jdbc.queryForObject("INSERT INTO items(name, unit, cost, reorder_level) VALUES (?, ?, ?, ?) RETURNING id",
            Long.class, name, unit, cost, reorderLevel);
    }

    @Transactional
    public boolean updateItem(long id, String unit, long cost, long reorderLevel) {
        return jdbc.update("UPDATE items SET unit = ?, cost = ?, reorder_level = ? WHERE id = ?", unit, cost, reorderLevel, id) == 1;
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
                   coalesce((SELECT sum(m.qty) FROM stock_movements m WHERE m.branch_id = ? AND m.item_id = i.id), 0) AS on_hand
            FROM items i WHERE i.active ORDER BY lower(i.name)
            """, (rs, i) -> {
                long onHand = rs.getLong("on_hand"), reorder = rs.getLong("reorder_level");
                return new StockRow(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getLong("cost"),
                    onHand, reorder, reorder > 0 && onHand <= reorder);
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
            throw new ApiException(HttpStatus.CONFLICT, "Only " + onHand(branchId, itemId) + " on hand. You cannot take out " + (-qty) + ".");
        }
        jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason, ref, user_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
            branchId, itemId, qty, kind, reason, ref, userId);
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
