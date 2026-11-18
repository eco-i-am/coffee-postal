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
                   @NotBlank String cost, @Min(0) @Max(1_000_000) long reorderLevel) {}
    record ItemUpdate(@NotBlank @Size(max = 20) String unit, @NotBlank String cost, @Min(0) @Max(1_000_000) long reorderLevel) {}

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
        long id = inventory.createItem(name, r.unit().trim(), cost, r.reorderLevel());
        audit.record(who.getName(), null, "ITEM_CREATE", "item", String.valueOf(id), null,
            "{\"name\":\"" + name.replace("\"", "'") + "\",\"cost\":" + cost + "}", null);
        return Map.of("id", id);
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_items_master_edit')")
    Map<String, Object> update(@PathVariable long id, @Valid @RequestBody ItemUpdate r, Principal who) {
        long cost = pesos(r.cost());
        if (!inventory.updateItem(id, r.unit().trim(), cost, r.reorderLevel())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That item no longer exists.");
        }
        audit.record(who.getName(), null, "ITEM_UPDATE", "item", String.valueOf(id), null,
            "{\"cost\":" + cost + ",\"reorderLevel\":" + r.reorderLevel() + "}", null);
        return Map.of("id", id);
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
