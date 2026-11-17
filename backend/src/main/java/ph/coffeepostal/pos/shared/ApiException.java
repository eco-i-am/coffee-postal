package ph.coffeepostal.pos.shared;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A problem the UI can show: a message, and optionally which form fields are wrong. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, String message) { this(status, message, Map.of()); }

    public ApiException(HttpStatus status, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.fields = fields;
    }

    public HttpStatus status() { return status; }
    public Map<String, String> fields() { return fields; }
}
