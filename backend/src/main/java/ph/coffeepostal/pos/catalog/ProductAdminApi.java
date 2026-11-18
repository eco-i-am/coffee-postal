package ph.coffeepostal.pos.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** HQ: products and categories. */
@RestController
@RequestMapping("/api/admin")
class ProductAdminApi {
    static final Set<String> ARTS = Set.of("cookie", "frap", "hot", "iced", "pastry", "sand");

    record ProductsPage(List<CatalogService.ProductRow> products, List<CatalogService.Category> categories, List<String> arts) {}
    record ProductReq(@NotBlank @Size(max = 120) String name, @NotNull Long categoryId, @NotBlank String price,
                      String cost, boolean hasSizes, @NotBlank String art) {}
    record CategoryReq(@NotBlank @Size(max = 80) String name) {}

    private final CatalogService catalog;
    private final AuditService audit;

    ProductAdminApi(CatalogService catalog, AuditService audit) { this.catalog = catalog; this.audit = audit; }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('PERM_products_view')")
    ProductsPage list() {
        return new ProductsPage(catalog.adminProducts(), catalog.categories(), ARTS.stream().sorted().toList());
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_products_add')")
    Map<String, Object> create(@Valid @RequestBody ProductReq r, Principal who) {
        String name = r.name().trim();
        long price = PesoInput.parse(r.price(), true, "price", "Enter a price like 99 or 99.50.");
        long cost = r.cost() == null || r.cost().isBlank() ? 0 : PesoInput.parse(r.cost(), true, "cost", "Enter a cost like 40 or 40.50.");
        if (!ARTS.contains(r.art())) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a picture from the list.", Map.of("art", "Pick one."));
        if (catalog.productNameExists(name)) throw new ApiException(HttpStatus.CONFLICT, "A product with that name already exists.", Map.of("name", "Already used."));
        long id = catalog.createProduct(r.categoryId(), name, price, cost, r.hasSizes(), r.art(), colorFor(r.art()));
        audit.record(who.getName(), null, "PRODUCT_CREATE", "product", String.valueOf(id), null,
            "{\"name\":\"" + name.replace("\"", "'") + "\",\"price\":" + price + "}", null);
        return Map.of("id", id);
    }

    @PostMapping("/products/{id}/toggle")
    @PreAuthorize("hasAuthority('PERM_products_edit')")
    Map<String, Object> toggle(@PathVariable long id, Principal who) {
        Boolean now = catalog.toggleProduct(id);
        if (now == null) throw new ApiException(HttpStatus.NOT_FOUND, "That product no longer exists.");
        audit.record(who.getName(), null, "PRODUCT_TOGGLE", "product", String.valueOf(id),
            "{\"active\":" + !now + "}", "{\"active\":" + now + "}", null);
        return Map.of("active", now);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_product_categories_add')")
    Map<String, Object> addCategory(@Valid @RequestBody CategoryReq r, Principal who) {
        String n = r.name().trim();
        if (catalog.categoryExists(n)) throw new ApiException(HttpStatus.CONFLICT, "That category already exists.", Map.of("name", "Already used."));
        long id = catalog.createCategory(n);
        audit.record(who.getName(), null, "CATEGORY_CREATE", "product_category", String.valueOf(id), null,
            "{\"name\":\"" + n.replace("\"", "'") + "\"}", null);
        return Map.of("id", id);
    }

    private static String colorFor(String art) {
        return switch (art) {
            case "iced", "cookie" -> "#8DCCC0";
            case "frap", "pastry" -> "#FDCDC1";
            case "sand" -> "#FFC74A";
            default -> "#FFA076";
        };
    }
}
