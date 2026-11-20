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
import org.springframework.web.bind.annotation.PutMapping;
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

    record ProductsPage(List<CatalogService.ProductRow> products, List<CatalogService.Category> categories, List<String> arts,
                        List<CatalogService.CategoryRow> allCategories, List<CatalogService.Header> headers) {}
    record ProductReq(@NotBlank @Size(max = 120) String name, @NotNull Long categoryId, @NotBlank String price,
                      String cost, boolean hasSizes, @NotBlank String art,
                      @Size(max = 40) String sku, @Size(max = 40) String barcode, @Size(max = 500) String description, @Size(max = 300) String tags) {}
    record ProductEditReq(@NotBlank @Size(max = 120) String name, @NotNull Long categoryId, @NotBlank String price,
                          String cost, boolean hasSizes, @NotBlank String art, boolean published,
                          @Size(max = 40) String sku, @Size(max = 40) String barcode, @Size(max = 500) String description, @Size(max = 300) String tags) {}
    record CategoryReq(@NotBlank @Size(max = 80) String name) {}
    record CategoryEditReq(@NotBlank @Size(max = 80) String name, Long headerId, int sortOrder, boolean active) {}
    record HeaderReq(@NotBlank @Size(max = 80) String name) {}
    record HeaderEditReq(@NotBlank @Size(max = 80) String name, int sortOrder, boolean active) {}

    private final CatalogService catalog;
    private final AuditService audit;

    ProductAdminApi(CatalogService catalog, AuditService audit) { this.catalog = catalog; this.audit = audit; }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('PERM_products_view')")
    ProductsPage list() {
        return new ProductsPage(catalog.adminProducts(), catalog.categories(), ARTS.stream().sorted().toList(),
            catalog.adminCategories(), catalog.headers());
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
        checkCodes(r.sku(), r.barcode(), -1);
        long id = catalog.createProduct(r.categoryId(), name, price, cost, r.hasSizes(), r.art(), colorFor(r.art()));
        catalog.setProductExtras(id, r.sku(), r.barcode(), r.description());
        catalog.setProductTags(id, r.tags());
        audit.record(who.getName(), null, "PRODUCT_CREATE", "product", String.valueOf(id), null,
            "{\"name\":\"" + name.replace("\"", "'") + "\",\"price\":" + price + "}", null);
        return Map.of("id", id);
    }

    /** Edit a product. A price change is audited with the old and new price. */
    @PutMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PERM_products_edit')")
    Map<String, Object> edit(@PathVariable long id, @Valid @RequestBody ProductEditReq r, Principal who) {
        CatalogService.ProductRow before = catalog.product(id);
        if (before == null) throw new ApiException(HttpStatus.NOT_FOUND, "That product no longer exists.");
        String name = r.name().trim();
        long price = PesoInput.parse(r.price(), true, "price", "Enter a price like 99 or 99.50.");
        long cost = r.cost() == null || r.cost().isBlank() ? 0 : PesoInput.parse(r.cost(), true, "cost", "Enter a cost like 40 or 40.50.");
        if (!ARTS.contains(r.art())) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a picture from the list.", Map.of("art", "Pick one."));
        if (catalog.productNameTaken(name, id)) throw new ApiException(HttpStatus.CONFLICT, "A product with that name already exists.", Map.of("name", "Already used."));
        checkCodes(r.sku(), r.barcode(), id);
        catalog.updateProduct(id, r.categoryId(), name, price, cost, r.hasSizes(), r.art(), colorFor(r.art()), r.published());
        catalog.setProductExtras(id, r.sku(), r.barcode(), r.description());
        catalog.setProductTags(id, r.tags());
        boolean priceChanged = before.price() != price || before.cost() != cost;
        audit.record(who.getName(), null, priceChanged ? "PRODUCT_PRICE_CHANGE" : "PRODUCT_UPDATE", "product", String.valueOf(id),
            "{\"price\":" + before.price() + ",\"cost\":" + before.cost() + ",\"published\":" + before.published() + "}",
            "{\"price\":" + price + ",\"cost\":" + cost + ",\"published\":" + r.published() + "}", null);
        return Map.of("id", id);
    }

    /** A SKU or barcode already on another product is refused, so a scan or a search finds one product. */
    private void checkCodes(String sku, String barcode, long exceptId) {
        if (catalog.productSkuTaken(sku, exceptId)) throw new ApiException(HttpStatus.CONFLICT, "That SKU is already on another product.", Map.of("sku", "Already used."));
        if (catalog.productBarcodeTaken(barcode, exceptId)) throw new ApiException(HttpStatus.CONFLICT, "That barcode is already on another product.", Map.of("barcode", "Already used."));
    }

    /** A draft copy of a product, to start a new one from. */
    @PostMapping("/products/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_products_add')")
    Map<String, Object> duplicate(@PathVariable long id, Principal who) {
        Map<String, Object> copy = catalog.duplicateProduct(id);
        if (copy == null) throw new ApiException(HttpStatus.NOT_FOUND, "That product no longer exists.");
        audit.record(who.getName(), null, "PRODUCT_DUPLICATE", "product", String.valueOf(copy.get("id")), null,
            "{\"from\":" + id + "}", null);
        return copy;
    }

    @GetMapping("/products/{id}/history")
    @PreAuthorize("hasAuthority('PERM_products_view')")
    List<CatalogService.PriceChange> history(@PathVariable long id) { return catalog.priceHistory(id); }

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

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('PERM_product_categories_edit')")
    Map<String, Object> editCategory(@PathVariable long id, @Valid @RequestBody CategoryEditReq r, Principal who) {
        String n = r.name().trim();
        if (catalog.categoryNameTaken(n, id)) throw new ApiException(HttpStatus.CONFLICT, "That category already exists.", Map.of("name", "Already used."));
        if (!catalog.updateCategory(id, n, r.headerId(), r.sortOrder(), r.active())) throw new ApiException(HttpStatus.NOT_FOUND, "That category no longer exists.");
        audit.record(who.getName(), null, "CATEGORY_UPDATE", "product_category", String.valueOf(id), null,
            "{\"name\":\"" + n.replace("\"", "'") + "\",\"active\":" + r.active() + "}", null);
        return Map.of("id", id);
    }

    @PostMapping("/headers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_product_headers_add')")
    Map<String, Object> addHeader(@Valid @RequestBody HeaderReq r, Principal who) {
        String n = r.name().trim();
        if (catalog.headerNameTaken(n, -1)) throw new ApiException(HttpStatus.CONFLICT, "That header already exists.", Map.of("name", "Already used."));
        long id = catalog.createHeader(n);
        audit.record(who.getName(), null, "HEADER_CREATE", "product_header", String.valueOf(id), null,
            "{\"name\":\"" + n.replace("\"", "'") + "\"}", null);
        return Map.of("id", id);
    }

    @PutMapping("/headers/{id}")
    @PreAuthorize("hasAuthority('PERM_product_headers_edit')")
    Map<String, Object> editHeader(@PathVariable long id, @Valid @RequestBody HeaderEditReq r, Principal who) {
        String n = r.name().trim();
        if (catalog.headerNameTaken(n, id)) throw new ApiException(HttpStatus.CONFLICT, "That header already exists.", Map.of("name", "Already used."));
        if (!catalog.updateHeader(id, n, r.sortOrder(), r.active())) throw new ApiException(HttpStatus.NOT_FOUND, "That header no longer exists.");
        audit.record(who.getName(), null, "HEADER_UPDATE", "product_header", String.valueOf(id), null,
            "{\"name\":\"" + n.replace("\"", "'") + "\",\"active\":" + r.active() + "}", null);
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
