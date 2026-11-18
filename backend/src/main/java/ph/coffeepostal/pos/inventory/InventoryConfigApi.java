package ph.coffeepostal.pos.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** HQ: item categories, the units list and each item's unit conversions. */
@RestController
@RequestMapping("/api/admin")
class InventoryConfigApi {
    record CategoryReq(@NotBlank @Size(max = 80) String name, Integer sortOrder, Boolean active) {}
    record UnitReq(@NotBlank @Size(max = 20) String code) {}
    record ConvReq(@NotBlank @Size(max = 20) String unit, @NotBlank String factor) {}
    record ConvRes(String unit, String factor) {}
    record Category(long id, String name, int sortOrder, boolean active, int items) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    InventoryConfigApi(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    @GetMapping("/item-categories")
    @PreAuthorize("hasAuthority('PERM_items_master_view')")
    List<Category> categories() {
        return jdbc.query("""
            SELECT c.id, c.name, c.sort_order, c.active, (SELECT count(*) FROM items i WHERE i.category_id = c.id)
            FROM item_categories c ORDER BY c.sort_order, lower(c.name)
            """, (rs, i) -> new Category(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4), rs.getInt(5)));
    }

    @PostMapping("/item-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_items_master_add')")
    Map<String, Object> addCategory(@Valid @RequestBody CategoryReq r, Principal who) {
        String name = r.name().trim();
        if (exists("item_categories", "name", name, -1)) throw new ApiException(HttpStatus.CONFLICT, "That category already exists.", Map.of("name", "Already used."));
        long id = jdbc.queryForObject("INSERT INTO item_categories(name, sort_order) VALUES (?, ?) RETURNING id", Long.class, name, r.sortOrder() == null ? 0 : r.sortOrder());
        audit.record(who.getName(), null, "ITEM_CATEGORY_CREATE", "item_category", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }

    @PostMapping("/item-categories/{id}")
    @PreAuthorize("hasAuthority('PERM_items_master_edit')")
    Map<String, Object> editCategory(@PathVariable long id, @Valid @RequestBody CategoryReq r, Principal who) {
        String name = r.name().trim();
        if (exists("item_categories", "name", name, id)) throw new ApiException(HttpStatus.CONFLICT, "That category already exists.", Map.of("name", "Already used."));
        int n = jdbc.update("UPDATE item_categories SET name = ?, sort_order = coalesce(?, sort_order), active = coalesce(?, active) WHERE id = ?",
            name, r.sortOrder(), r.active(), id);
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That category no longer exists.");
        audit.record(who.getName(), null, "ITEM_CATEGORY_UPDATE", "item_category", String.valueOf(id), null, null, null);
        return Map.of("id", id);
    }

    @GetMapping("/units")
    @PreAuthorize("hasAuthority('PERM_items_master_view')")
    List<String> units() { return jdbc.queryForList("SELECT code FROM units ORDER BY lower(code)", String.class); }

    @PostMapping("/units")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_items_master_add')")
    Map<String, Object> addUnit(@Valid @RequestBody UnitReq r, Principal who) {
        String code = r.code().trim();
        if (exists("units", "code", code, -1)) throw new ApiException(HttpStatus.CONFLICT, "That unit already exists.", Map.of("code", "Already used."));
        jdbc.update("INSERT INTO units(code) VALUES (?)", code);
        audit.record(who.getName(), null, "UNIT_CREATE", "unit", code, null, null, null);
        return Map.of("code", code);
    }

    @GetMapping("/items/{id}/conversions")
    @PreAuthorize("hasAuthority('PERM_items_master_view')")
    List<ConvRes> conversions(@PathVariable long id) {
        return jdbc.query("SELECT unit, factor FROM item_conversions WHERE item_id = ? ORDER BY lower(unit)",
            (rs, i) -> new ConvRes(rs.getString(1), InventoryService.show(rs.getLong(2))), id);
    }

    /** Replaces all of an item's conversions. "1 box = 12 pcs" is unit box, factor 12. */
    @PutMapping("/items/{id}/conversions")
    @Transactional
    @PreAuthorize("hasAuthority('PERM_items_master_edit')")
    List<ConvRes> saveConversions(@PathVariable long id, @RequestBody List<@Valid ConvReq> rows, Principal who) {
        String base = jdbc.query("SELECT unit FROM items WHERE id = ?", (rs, i) -> rs.getString(1), id).stream().findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That item no longer exists."));
        Set<String> seen = new HashSet<>();
        jdbc.update("DELETE FROM item_conversions WHERE item_id = ?", id);
        for (ConvReq c : rows) {
            String unit = c.unit().trim();
            if (unit.equalsIgnoreCase(base)) throw new ApiException(HttpStatus.BAD_REQUEST, unit + " is already this item's own unit.");
            if (!seen.add(unit.toLowerCase())) throw new ApiException(HttpStatus.BAD_REQUEST, "Each unit can be listed once.");
            long f = thousandths(c.factor());
            jdbc.update("INSERT INTO item_conversions(item_id, unit, factor) VALUES (?, ?, ?)", id, unit, f);
            jdbc.update("INSERT INTO units(code) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM units WHERE lower(code) = lower(?))", unit, unit);
        }
        audit.record(who.getName(), null, "ITEM_CONVERSIONS", "item", String.valueOf(id), null, "{\"count\":" + rows.size() + "}", null);
        return conversions(id);
    }

    static long thousandths(String s) {
        try {
            BigDecimal d = new BigDecimal(s.trim());
            if (d.scale() > 3 || d.signum() <= 0 || d.compareTo(BigDecimal.valueOf(1_000_000)) > 0) throw new NumberFormatException();
            return d.movePointRight(3).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter how many of the item's own unit make one, like 12 or 0.5.");
        }
    }

    private boolean exists(String table, String col, String value, long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE lower(" + col + ") = lower(?) AND id <> ?", Integer.class, value, exceptId);
        return n != null && n > 0;
    }
}
