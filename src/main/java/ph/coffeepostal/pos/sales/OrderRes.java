package ph.coffeepostal.pos.sales;

/** All amounts in centavos. */
public record OrderRes(long orderNo, long subtotal, long discount, long total, long vatable, long vat, long change) {
}
