package ph.coffeepostal.pos.iam;

import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import ph.coffeepostal.pos.shared.ApiException;

/** Rules for passwords and PINs. Kept simple enough for a cashier to live with. */
final class PasswordPolicy {
    private PasswordPolicy() {}

    private static final Set<String> COMMON = Set.of("password1", "12345678", "11111111", "qwerty123", "coffee123", "password123", "iloveyou1");

    static void checkPassword(String pw, String field) {
        String msg = null;
        if (pw == null || pw.length() < 8) msg = "Use at least 8 characters.";
        else if (pw.length() > 100) msg = "That is too long.";
        else if (!pw.matches(".*[A-Za-z].*") || !pw.matches(".*\\d.*")) msg = "Use at least one letter and one number.";
        else if (COMMON.contains(pw.toLowerCase())) msg = "That password is too easy to guess.";
        if (msg != null) throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
    }

    /** A PIN is 4 to 6 digits and is not something like 1111 or 1234. */
    static void checkPin(String pin, String field) {
        String msg = null;
        if (pin == null || !pin.matches("\\d{4,6}")) msg = "Use 4 to 6 digits.";
        else if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin)) msg = "That PIN is too easy to guess.";
        if (msg != null) throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
    }
}
