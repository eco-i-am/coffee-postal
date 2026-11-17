package ph.coffeepostal.pos.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.LinkedHashMap;
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
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** HQ: what one sale of a product uses up. Quantities are written in the item's own unit, e.g. 0.018 of a 1 kg bag. */
@RestController
@RequestMapping("/api/admin/products/{productId}/recipe")
class RecipeAdminApi {
    record LineReq(@NotNull Long itemId, @NotBlank String qty) {}
    record SaveReq(@NotNull @Size(max = 30) List<@Valid LineReq> lines) {}

    private final InventoryService inventory;
    private final AuditService audit;

    RecipeAdminApi(InventoryService inventory, AuditService audit) { this.inventory = inventory; this.audit = audit; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_products_view')")
    List<InventoryService.RecipeLine> get(@PathVariable long productId) {
        if (!inventory.productExists(productId)) throw new ApiException(HttpStatus.NOT_FOUND, "That product no longer exists.");
        return inventory.recipe(productId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_products_edit')")
    Map<String, Object> save(@PathVariable long productId, @Valid @RequestBody SaveReq r, Principal who) {
        if (!inventory.productExists(productId)) throw new ApiException(HttpStatus.NOT_FOUND, "That product no longer exists.");
        Map<Long, Long> q = new LinkedHashMap<>();
        for (LineReq l : r.lines()) {
            long milli = parse(l.qty());
            if (q.put(l.itemId(), milli) != null) throw new ApiException(HttpStatus.BAD_REQUEST, "An item appears twice in this recipe.");
        }
        inventory.saveRecipe(productId, q);
        audit.record(who.getName(), null, "RECIPE_SAVE", "product", String.valueOf(productId), null, "{\"items\":" + q.size() + "}", null);
        return Map.of("items", q.size());
    }

    /** "0.018" -> 18. At most 3 decimals, greater than zero, at most 1000 units per sale. */
    private static long parse(String s) {
        try {
            BigDecimal v = new BigDecimal(s.trim());
            BigDecimal milli = v.multiply(BigDecimal.valueOf(InventoryService.SCALE));
            if (v.signum() <= 0 || milli.stripTrailingZeros().scale() > 0 || v.compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw new IllegalArgumentException();
            }
            return milli.longValueExact();
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter an amount like 0.018 (up to 3 decimals, more than zero).");
        }
    }
}
