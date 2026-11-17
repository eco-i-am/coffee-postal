package ph.coffeepostal.pos.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.Money;

/** HQ: products and categories. */
@Controller
class ProductAdminController {
    static final Set<String> ARTS = Set.of("hot", "iced", "frap", "pastry", "cookie", "sand");

    private final CatalogService catalog;
    private final AuditService audit;

    ProductAdminController(CatalogService catalog, AuditService audit) { this.catalog = catalog; this.audit = audit; }

    public static class ProductForm {
        @NotBlank @Size(max = 120) private String name = "";
        @NotNull private Long categoryId;
        @NotBlank private String price = "";
        private String cost = "";
        private boolean hasSizes;
        private String art = "hot";
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long v) { categoryId = v; }
        public String getPrice() { return price; }
        public void setPrice(String v) { price = v; }
        public String getCost() { return cost; }
        public void setCost(String v) { cost = v; }
        public boolean isHasSizes() { return hasSizes; }
        public void setHasSizes(boolean v) { hasSizes = v; }
        public String getArt() { return art; }
        public void setArt(String v) { art = v; }
    }

    private void fill(Model m) {
        m.addAttribute("products", catalog.adminProducts());
        m.addAttribute("categories", catalog.categories());
        m.addAttribute("arts", ARTS.stream().sorted().toList());
    }

    @GetMapping("/admin/products")
    @PreAuthorize("hasAuthority('PERM_products_view')")
    String list(Model m) {
        fill(m);
        if (!m.containsAttribute("form")) m.addAttribute("form", new ProductForm());
        return "admin/products";
    }

    @PostMapping("/admin/products")
    @PreAuthorize("hasAuthority('PERM_products_add')")
    String create(@Valid @ModelAttribute("form") ProductForm f, BindingResult br, Principal who, Model m, RedirectAttributes ra) {
        long price = 0, cost = 0;
        if (!br.hasErrors()) {
            try { price = pesos(f.getPrice()); } catch (RuntimeException e) { br.rejectValue("price", "bad", "Enter a price like 99 or 99.50."); }
            try { cost = f.getCost() == null || f.getCost().isBlank() ? 0 : pesos(f.getCost()); } catch (RuntimeException e) { br.rejectValue("cost", "bad", "Enter a cost like 40 or 40.50."); }
            if (!ARTS.contains(f.getArt())) br.rejectValue("art", "bad", "Pick a picture from the list.");
            if (catalog.productNameExists(f.getName().trim())) br.rejectValue("name", "dup", "A product with that name already exists.");
        }
        if (br.hasErrors()) { fill(m); return "admin/products"; }
        long id = catalog.createProduct(f.getCategoryId(), f.getName().trim(), price, cost, f.isHasSizes(), f.getArt(), colorFor(f.getArt()));
        audit.record(who.getName(), null, "PRODUCT_CREATE", "product", String.valueOf(id), null,
            "{\"name\":\"" + f.getName().trim().replace("\"", "'") + "\",\"price\":" + price + "}", null);
        ra.addFlashAttribute("msg", f.getName().trim() + " added to the menu.");
        return "redirect:/admin/products";
    }

    @PostMapping("/admin/products/{id}/toggle")
    @PreAuthorize("hasAuthority('PERM_products_edit')")
    String toggle(@PathVariable long id, Principal who, RedirectAttributes ra) {
        Boolean now = catalog.toggleProduct(id);
        if (now == null) { ra.addFlashAttribute("msg", "That product no longer exists."); return "redirect:/admin/products"; }
        audit.record(who.getName(), null, "PRODUCT_TOGGLE", "product", String.valueOf(id),
            "{\"active\":" + !now + "}", "{\"active\":" + now + "}", null);
        ra.addFlashAttribute("msg", now ? "Product is back on the menu." : "Product is off the menu.");
        return "redirect:/admin/products";
    }

    @PostMapping("/admin/categories")
    @PreAuthorize("hasAuthority('PERM_product_categories_add')")
    String addCategory(@RequestParam String name, Principal who, RedirectAttributes ra) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 80) { ra.addFlashAttribute("msg", "Enter a category name."); return "redirect:/admin/products"; }
        if (catalog.categoryExists(n)) { ra.addFlashAttribute("msg", "That category already exists."); return "redirect:/admin/products"; }
        long id = catalog.createCategory(n);
        audit.record(who.getName(), null, "CATEGORY_CREATE", "product_category", String.valueOf(id), null, "{\"name\":\"" + n.replace("\"", "'") + "\"}", null);
        ra.addFlashAttribute("msg", n + " category added.");
        return "redirect:/admin/products";
    }

    private static long pesos(String s) {
        BigDecimal v = new BigDecimal(s.trim());
        if (v.signum() < 0 || v.scale() > 2) throw new IllegalArgumentException();
        return Money.ofPesos(v.toPlainString()).centavos();
    }

    private static String colorFor(String art) {
        return switch (art) {
            case "iced" -> "#8DCCC0";
            case "frap" -> "#FDCDC1";
            case "pastry" -> "#FDCDC1";
            case "cookie" -> "#8DCCC0";
            case "sand" -> "#FFC74A";
            default -> "#FFA076";
        };
    }
}
