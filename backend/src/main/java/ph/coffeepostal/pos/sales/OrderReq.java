package ph.coffeepostal.pos.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** What the POS sends. Note: no prices. The server prices the order. */
public record OrderReq(
        @NotNull UUID id,
        @NotNull String orderType,        // DINE_IN | TAKEOUT | DELIVERY
        @NotNull String discountType,     // NONE or the code of an active discount
        @NotEmpty @Size(max = 60) List<@Valid OrderLineReq> lines,
        @NotNull String paymentMethod,    // CASH | GCASH | MAYA | CARD
        Long tendered,                    // cash handed over, centavos (cash only)
        @Size(max = 80) String reference,
        java.time.Instant soldAt,         // when the sale really happened
        @Size(max = 40) String discountIdNo,   // Senior/PWD ID number, when the discount asks for it
        @Size(max = 12) String managerPin) {   // a manager PIN, for a discount over its limit; never queued offline          // when the sale really happened; only sent by an order that waited offline
}
