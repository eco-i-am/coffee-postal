package ph.coffeepostal.pos.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.Money;
import ph.coffeepostal.pos.shared.PesoInput;

/** HQ: the master list of items branches can order. */
@RestController
@RequestMapping("/api/admin/items")
class ItemAdminApi {
    record ItemReq(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 20) String unit,
                   @NotBlank String cost, @Min(0) @Max(1_000_000) long reorderLevel, Long categoryId,
                   @Size(max = 40) String sku, @Size(max = 40) String barcode, @Size(max = 500) String description) {}
    record ItemUpdate(@NotBlank @Size(max = 20) String unit, @NotBlank String cost, @Min(0) @Max(1_000_000) long reorderLevel, Long categoryId,
                      @Size(max = 40) String sku, @Size(max = 40) String barcode, @Size(max = 500) String description) {}

    private final InventoryService inventory;
    private final AuditService audit;

    ItemAdminApi(InventoryService inventory, AuditService audit) { this.inventory = inventory; this.audit = audit; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_items_master_view')")
    List<InventoryService.ItemRow> list() { return inventory.items(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_items_master_add')")
    Map<String, Object> create(@Valid @RequestBody ItemReq r, Principal who) {
        String name = r.name().trim();
        long cost = pesos(r.cost());
        if (inventory.itemNameExists(name, null)) {
            throw new ApiException(HttpStatus.CONFLICT, "An item with that name already exists.", Map.of("name", "Already used."));
        }
        checkCodes(r.sku(), r.barcode(), -1);
        long id = inventory.createItem(name, r.unit().trim(), cost, r.reorderLevel(), r.categoryId());
        inventory.setItemExtras(id, r.sku(), r.barcode(), r.description());
        audit.record(who.getName(), null, "ITEM_CREATE", "item", String.valueOf(id), null,
            "{\"name\":\"" + name.replace("\"", "'") + "\",\"cost\":" + cost + "}", null);
        return Map.of("id", id);
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_items_master_edit')")
    Map<String, Object> update(@PathVariable long id, @Valid @RequestBody ItemUpdate r, Principal who) {
        long cost = pesos(r.cost());
        InventoryService.ItemRow before = inventory.items().stream().filter(i -> i.id() == id).findFirst().orElse(null);
        if (before == null) throw new ApiException(HttpStatus.NOT_FOUND, "That item no longer exists.");
        checkCodes(r.sku(), r.barcode(), id);
        if (!inventory.updateItem(id, r.unit().trim(), cost, r.reorderLevel(), r.categoryId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That item no longer exists.");
        }
        inventory.setItemExtras(id, r.sku(), r.barcode(), r.description());
        audit.record(who.getName(), null, before.cost() != cost ? "ITEM_COST_CHANGE" : "ITEM_UPDATE", "item", String.valueOf(id),
            "{\"cost\":" + before.cost() + ",\"reorderLevel\":" + before.reorderLevel() + "}",
            "{\"cost\":" + cost + ",\"reorderLevel\":" + r.reorderLevel() + "}", null);
        return Map.of("id", id);
    }

    private void checkCodes(String sku, String barcode, long exceptId) {
        if (inventory.itemSkuTaken(sku, exceptId)) throw new ApiException(HttpStatus.CONFLICT, "That SKU is already on another item.", Map.of("sku", "Already used."));
        if (inventory.itemBarcodeTaken(barcode, exceptId)) throw new ApiException(HttpStatus.CONFLICT, "That barcode is already on another item.", Map.of("barcode", "Already used."));
    }

    /** A copy of an item to start a new one from. */
    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_items_master_add')")
    Map<String, Object> duplicate(@PathVariable long id, Principal who) {
        Map<String, Object> copy = inventory.duplicateItem(id);
        if (copy == null) throw new ApiException(HttpStatus.NOT_FOUND, "That item no longer exists.");
        audit.record(who.getName(), null, "ITEM_DUPLICATE", "item", String.valueOf(copy.get("id")), null, "{\"from\":" + id + "}", null);
        return copy;
    }

    @GetMapping("/{id}/movements")
    @PreAuthorize("hasAuthority('PERM_items_master_view')")
    List<InventoryService.HqMovement> movements(@PathVariable long id) { return inventory.allMovements(id); }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('PERM_items_master_view')")
    List<InventoryService.CostChange> history(@PathVariable long id) { return inventory.costHistory(id); }

    /** HQ decides whether branches can put this item on a purchase order. */
    @PostMapping("/{id}/orderable")
    @PreAuthorize("hasAuthority('PERM_po_items_edit')")
    Map<String, Object> orderable(@PathVariable long id, @RequestBody Map<String, Boolean> r, Principal who) {
        boolean on = Boolean.TRUE.equals(r.get("orderable"));
        Boolean was = inventory.setOrderable(id, on);
        if (was == null) throw new ApiException(HttpStatus.NOT_FOUND, "That item no longer exists.");
        audit.record(who.getName(), null, "ITEM_ORDERABLE", "item", String.valueOf(id), "{\"orderable\":" + was + "}", "{\"orderable\":" + on + "}", null);
        return Map.of("orderable", on);
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('PERM_items_master_edit')")
    Map<String, Object> toggle(@PathVariable long id, Principal who) {
        Boolean now = inventory.toggleItem(id);
        if (now == null) throw new ApiException(HttpStatus.NOT_FOUND, "That item no longer exists.");
        audit.record(who.getName(), null, "ITEM_TOGGLE", "item", String.valueOf(id),
            "{\"active\":" + !now + "}", "{\"active\":" + now + "}", null);
        return Map.of("active", now);
    }

    private static long pesos(String s) {
        return PesoInput.parse(s, true, "cost", "Enter a cost like 850 or 850.50.");
    }
}
