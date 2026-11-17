package ph.coffeepostal.pos.sales;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OrderLineReq(@NotNull Long productId, Long variantId, @Size(max = 10) List<Long> addonIds,
                           @Min(1) @Max(99) int qty) {
}
