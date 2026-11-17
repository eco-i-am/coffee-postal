package ph.coffeepostal.pos.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** Branch: Item Inventory. Always scoped to the signed-in user's branch. */
@RestController
@RequestMapping("/api/branch/inventory")
class BranchInventoryApi {
    /** kind ADJUST: delta is signed (count correction). kind WASTE: delta is the positive amount thrown away. */
    record AdjustReq(@NotNull Long itemId, @NotNull Long delta, @NotBlank String kind, @NotBlank @Size(max = 200) String reason) {}

    private final InventoryService inventory;
    private final OrderService orders;
    private final AuditService audit;

    BranchInventoryApi(InventoryService inventory, OrderService orders, AuditService audit) {
        this.inventory = inventory; this.orders = orders; this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_branch_inventory_view')")
    List<InventoryService.StockRow> stock(Principal who) {
        return inventory.stock(orders.whoIs(who.getName()).branchId());
    }

    @GetMapping("/{itemId}/movements")
    @PreAuthorize("hasAuthority('PERM_branch_inventory_view')")
    List<InventoryService.Movement> movements(@PathVariable long itemId, Principal who) {
        return inventory.movements(orders.whoIs(who.getName()).branchId(), itemId);
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasAuthority('PERM_branch_inventory_edit')")
    Map<String, Object> adjust(@Valid @RequestBody AdjustReq r, Principal who) {
        var me = orders.whoIs(who.getName());
        if (!r.kind().equals("ADJUST") && !r.kind().equals("WASTE")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick adjust or waste.", Map.of("kind", "Pick one."));
        }
        if (r.delta() == 0 || Math.abs(r.delta()) > 1_000_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a quantity.", Map.of("delta", "Enter a quantity."));
        }
        if (r.kind().equals("WASTE") && r.delta() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter how many were thrown away as a positive number.", Map.of("delta", "Use a positive number."));
        }
        if (!inventory.itemActive(r.itemId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That item is not available.");
        }
        long qty = (r.kind().equals("WASTE") ? -r.delta() : r.delta()) * InventoryService.SCALE;
        long before = inventory.onHand(me.branchId(), r.itemId());
        inventory.move(me.branchId(), r.itemId(), qty, r.kind(), r.reason().trim(), null, me.userId());
        audit.record(who.getName(), me.branchId(), "STOCK_" + r.kind(), "item", String.valueOf(r.itemId()),
            "{\"onHand\":" + before + "}", "{\"onHand\":" + (before + qty) + "}", null);
        return Map.of("onHand", before + qty);
    }
}
