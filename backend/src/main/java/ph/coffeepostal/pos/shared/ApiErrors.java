package ph.coffeepostal.pos.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One error shape for the UI: { "message": "...", "fields": { "name": "..." } }. */
@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage(), "fields", e.fields()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), "Please fill this in correctly."));
        return ResponseEntity.badRequest().body(Map.of("message", "Please check the form.", "fields", fields));
    }
}
