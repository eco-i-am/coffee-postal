package ph.coffeepostal.pos.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is stored and computed as integer centavos. Never use floating point. */
public record Money(long centavos) implements Comparable<Money> {
    public static final Money ZERO = new Money(0);

    public static Money ofPesos(String pesos) {
        return new Money(new BigDecimal(pesos).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
    public Money plus(Money o) { return new Money(Math.addExact(centavos, o.centavos)); }
    public Money minus(Money o) { return new Money(Math.subtractExact(centavos, o.centavos)); }
    public Money times(long qty) { return new Money(Math.multiplyExact(centavos, qty)); }
    /** e.g. percent(12) for VAT; rounds half-up to the nearest centavo. */
    public Money percent(BigDecimal pct) {
        return new Money(BigDecimal.valueOf(centavos).multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact());
    }
    public BigDecimal pesos() { return BigDecimal.valueOf(centavos, 2); }
    @Override public int compareTo(Money o) { return Long.compare(centavos, o.centavos); }
    @Override public String toString() { return "PHP " + pesos().toPlainString(); }
}
