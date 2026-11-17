package ph.coffeepostal.pos.sales;

import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.catalog.CatalogService;

/** JSON endpoints used by the cashier screen. */
@RestController
@RequestMapping("/api/pos")
class PosApi {
    private final CatalogService catalog;
    private final OrderService orders;

    PosApi(CatalogService catalog, OrderService orders) { this.catalog = catalog; this.orders = orders; }

    @GetMapping("/menu")
    @PreAuthorize("hasAuthority('PERM_pos_view')")
    CatalogService.Menu menu(Principal who) {
        return catalog.menu(orders.whoIs(who.getName()).branchId());
    }

    @PostMapping("/orders")
    @PreAuthorize("hasAuthority('PERM_pos_add')")
    OrderRes place(@Valid @RequestBody OrderReq req, Principal who) {
        return orders.place(who.getName(), req);
    }
}
