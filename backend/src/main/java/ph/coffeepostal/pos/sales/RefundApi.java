package ph.coffeepostal.pos.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/branch/orders")
class RefundApi {
    record RefundReq(@NotBlank @Size(max = 300) String reason, @NotBlank @Size(max = 12) String pin) {}

    private final RefundService refunds;

    RefundApi(RefundService refunds) { this.refunds = refunds; }

    /** Any branch user who can see orders may START a refund; the manager PIN is what authorises it. */
    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('PERM_orders_view')")
    Map<String, Object> refund(@PathVariable UUID id, @Valid @RequestBody RefundReq r, Principal who) {
        refunds.refund(id, who.getName(), r.reason(), r.pin());
        return Map.of("status", "REFUNDED");
    }
}
