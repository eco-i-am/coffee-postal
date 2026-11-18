package ph.coffeepostal.pos.hr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ph.coffeepostal.pos.shared.ApiException;

/**
 * Time clock PINs: 6 digits, unique within a branch. The PIN is never stored.
 *  - lookup: HMAC-SHA256 keyed with a server secret, so the database alone cannot be used to read PINs back;
 *  - check: SHA-256(salt:pin), sent to the branch tablet so it can recognise a PIN with no internet.
 */
@Component
public class PinCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] secret;

    public PinCodec(@Value("${app.timeclock.pin-secret:}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("Set app.timeclock.pin-secret (env TIMECLOCK_PIN_SECRET) to a random value of at least 16 characters.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 6 digits, not the same digit repeated and not a run like 123456 or 654321. */
    public static void validate(String pin, String field) {
        String msg = null;
        if (pin == null || !pin.matches("\\d{6}")) msg = "Use exactly 6 digits.";
        else if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin)) msg = "That PIN is too easy to guess.";
        if (msg != null) throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
    }

    public String lookup(long branchId, String pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((branchId + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String newSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public String check(String salt, String pin) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
