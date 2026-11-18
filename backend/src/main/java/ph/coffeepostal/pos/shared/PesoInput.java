package ph.coffeepostal.pos.shared;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Parses a peso amount typed by a person (a string like "99" or "99.50") into centavos. */
public final class PesoInput {
    private PesoInput() {}

    /** Amounts above this are refused as typing mistakes unless a caller sets its own cap. */
    public static final BigDecimal DEFAULT_MAX = new BigDecimal("100000000");

    public static long parse(String s, boolean allowZero, String field, String message) {
        return parse(s, allowZero, DEFAULT_MAX, field, message);
    }

    public static long parse(String s, boolean allowZero, BigDecimal maxPesos, String field, String message) {
        try {
            BigDecimal v = new BigDecimal(s.trim());
            if (v.signum() < 0 || (!allowZero && v.signum() == 0) || v.scale() > 2 || v.compareTo(maxPesos) > 0) {
                throw new IllegalArgumentException();
            }
            return Money.ofPesos(v.toPlainString()).centavos();
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
        }
    }
}
