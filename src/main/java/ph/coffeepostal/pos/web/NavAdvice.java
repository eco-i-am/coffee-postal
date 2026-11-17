package ph.coffeepostal.pos.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Builds the sidebar for whoever is signed in. Items for later phases show as "soon". */
@ControllerAdvice
class NavAdvice {
    public record NavItem(String key, String label, String href, boolean enabled) {}

    @ModelAttribute("nav")
    List<NavItem> nav(Authentication a) {
        List<NavItem> out = new ArrayList<>();
        if (a == null) return out;
        boolean admin = has(a, "SCOPE_ADMIN"), branch = has(a, "SCOPE_BRANCH");
        if (admin) {
            out.add(new NavItem("dashboard", "Dashboard", "/admin", true));
            if (has(a, "PERM_branches_view")) out.add(new NavItem("branches", "Branches", "/admin/branches", true));
            if (has(a, "PERM_products_view")) out.add(new NavItem("products", "Products", "/admin/products", true));
            out.add(new NavItem("bpo", "Branch PO", "#", false));
            out.add(new NavItem("inventory", "Inventory", "#", false));
            out.add(new NavItem("reports", "Reports", "#", false));
        } else if (branch) {
            out.add(new NavItem("dashboard", "Dashboard", "/branch", true));
            if (has(a, "PERM_pos_view")) out.add(new NavItem("pos", "Point of Sale", "/pos", true));
            out.add(new NavItem("orders", "Order List", "#", false));
            out.add(new NavItem("stock", "Item Inventory", "#", false));
            out.add(new NavItem("po", "Purchase Orders", "#", false));
            out.add(new NavItem("reports", "Reports", "#", false));
        }
        return out;
    }

    @ModelAttribute("who")
    String who(Authentication a) { return a == null ? "" : a.getName(); }

    private static boolean has(Authentication a, String auth) {
        return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals(auth));
    }
}
