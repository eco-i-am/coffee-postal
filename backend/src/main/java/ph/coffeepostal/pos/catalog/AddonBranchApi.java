package ph.coffeepostal.pos.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
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
import ph.coffeepostal.pos.shared.PesoInput;

/** HQ: add-ons, which add-ons each product offers, and per-branch price and availability. */
@RestController
@RequestMapping("/api/admin")
class AddonBranchApi {
    record Addon(long id, String name, long price, boolean active) {}
    record AddonReq(@NotBlank @Size(max = 80) String name, @NotBlank String price, boolean active) {}
    record ProductAddonsReq(List<Long> addonIds) {}
    record BranchRow(long branchId, String code, String name, boolean available, Long price) {}
    record BranchReq(long branchId, boolean available, String price) {}
    record BranchesReq(List<BranchReq> branches) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    AddonBranchApi(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    @GetMapping("/addons")
    @PreAuthorize("hasAuthority('PERM_product_addons_view')")
    List<Addon> addons() {
        return jdbc.query("SELECT id, name, price, active FROM addons ORDER BY name",
            (rs, i) -> new Addon(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4)));
    }

    @PostMapping("/addons")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_product_addons_add')")
    Map<String, Object> add(@Valid @RequestBody AddonReq r, Principal who) {
        String name = r.name().trim();
        long price = PesoInput.parse(r.price(), true, "price", "Enter a price like 15 or 15.50.");
        if (nameTaken(name, -1)) throw new ApiException(HttpStatus.CONFLICT, "That add-on already exists.", Map.of("name", "Already used."));
        long id = jdbc.queryForObject("INSERT INTO addons(name, price) VALUES (?, ?) RETURNING id", Long.class, name, price);
        audit.record(who.getName(), null, "ADDON_CREATE", "addon", String.valueOf(id), null, "{\"price\":" + price + "}", null);
        return Map.of("id", id);
    }

    @PutMapping("/addons/{id}")
    @PreAuthorize("hasAuthority('PERM_product_addons_edit')")
    Map<String, Object> edit(@PathVariable long id, @Valid @RequestBody AddonReq r, Principal who) {
        String name = r.name().trim();
        long price = PesoInput.parse(r.price(), true, "price", "Enter a price like 15 or 15.50.");
        if (nameTaken(name, id)) throw new ApiException(HttpStatus.CONFLICT, "That add-on already exists.", Map.of("name", "Already used."));
        List<Long> before = jdbc.queryForList("SELECT price FROM addons WHERE id = ?", Long.class, id);
        if (before.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That add-on no longer exists.");
        jdbc.update("UPDATE addons SET name = ?, price = ?, active = ? WHERE id = ?", name, price, r.active(), id);
        audit.record(who.getName(), null, "ADDON_UPDATE", "addon", String.valueOf(id),
            "{\"price\":" + before.get(0) + "}", "{\"price\":" + price + ",\"active\":" + r.active() + "}", null);
        return Map.of("id", id);
    }

    @GetMapping("/products/{id}/addons")
    @PreAuthorize("hasAuthority('PERM_products_view')")
    List<Long> productAddons(@PathVariable long id) {
        return jdbc.queryForList("SELECT addon_id FROM product_addons WHERE product_id = ? ORDER BY addon_id", Long.class, id);
    }

    @PutMapping("/products/{id}/addons")
    @PreAuthorize("hasAuthority('PERM_products_edit')")
    @Transactional
    Map<String, Object> setProductAddons(@PathVariable long id, @RequestBody ProductAddonsReq r, Principal who) {
        requireProduct(id);
        List<Long> ids = r.addonIds() == null ? List.of() : r.addonIds().stream().distinct().toList();
        jdbc.update("DELETE FROM product_addons WHERE product_id = ?", id);
        for (Long a : ids) {
            int n = jdbc.update("INSERT INTO product_addons(product_id, addon_id) SELECT ?, id FROM addons WHERE id = ?", id, a);
            if (n == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown add-on.");
        }
        audit.record(who.getName(), null, "PRODUCT_ADDONS", "product", String.valueOf(id), null, "{\"count\":" + ids.size() + "}", null);
        return Map.of("count", ids.size());
    }

    @GetMapping("/products/{id}/branches")
    @PreAuthorize("hasAuthority('PERM_products_view')")
    List<BranchRow> branches(@PathVariable long id) {
        requireProduct(id);
        return jdbc.query("""
            SELECT b.id, b.code, b.name, coalesce(bp.available, TRUE) AS available, bp.price
            FROM branches b LEFT JOIN branch_products bp ON bp.branch_id = b.id AND bp.product_id = ?
            WHERE b.active ORDER BY b.code
            """, (rs, i) -> new BranchRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("available"), (Long) rs.getObject("price")), id);
    }

    /** Sets availability and an optional branch price. A branch that is available at the HQ price has no row. */
    @PutMapping("/products/{id}/branches")
    @PreAuthorize("hasAuthority('PERM_products_edit')")
    @Transactional
    Map<String, Object> setBranches(@PathVariable long id, @RequestBody BranchesReq r, Principal who) {
        requireProduct(id);
        List<BranchReq> rows = r.branches() == null ? List.of() : r.branches();
        for (BranchReq b : rows) {
            Long price = b.price() == null || b.price().isBlank() ? null : PesoInput.parse(b.price(), true, "price", "Enter a price like 99 or 99.50.");
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM branches WHERE id = ?", Integer.class, b.branchId());
            if (exists == null || exists == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown branch.");
            if (b.available() && price == null) {
                jdbc.update("DELETE FROM branch_products WHERE branch_id = ? AND product_id = ?", b.branchId(), id);
            } else {
                jdbc.update("""
                    INSERT INTO branch_products(branch_id, product_id, available, price) VALUES (?, ?, ?, ?)
                    ON CONFLICT (branch_id, product_id) DO UPDATE SET available = EXCLUDED.available, price = EXCLUDED.price
                    """, b.branchId(), id, b.available(), price);
            }
            audit.record(who.getName(), b.branchId(), "BRANCH_PRODUCT_SET", "product", String.valueOf(id), null,
                "{\"available\":" + b.available() + ",\"price\":" + price + "}", null);
        }
        return Map.of("updated", rows.size());
    }

    private boolean nameTaken(String name, long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM addons WHERE lower(name) = lower(?) AND id <> ?", Integer.class, name, exceptId);
        return n != null && n > 0;
    }

    private void requireProduct(long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?", Integer.class, id);
        if (n == null || n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That product no longer exists.");
    }
}
