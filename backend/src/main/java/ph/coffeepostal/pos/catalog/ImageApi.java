package ph.coffeepostal.pos.catalog;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/**
 * One optional photo per menu product and per inventory item. Anyone signed in can see the photos (the POS tiles and
 * the stock screens use them). Only people who may edit the product or item can change them.
 * The photo URL carries ?v=<updated time>, so the browser (and the offline cache) can keep a copy until it changes.
 */
@RestController
class ImageApi {
    static final int MAX_BYTES = 400_000;

    record Entry(String type, long id, long ver) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    ImageApi(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    /** Which products and items have a photo, and a version number for each. Small, so the screens load it once. */
    @GetMapping("/api/pos/images")
    List<Entry> index() {
        return jdbc.query("SELECT owner_type, owner_id, (extract(epoch FROM updated_at) * 1000)::bigint AS ver FROM item_images",
            (rs, i) -> new Entry(rs.getString("owner_type").toLowerCase(), rs.getLong("owner_id"), rs.getLong("ver")));
    }

    @GetMapping("/api/pos/images/{type}/{id}")
    ResponseEntity<byte[]> get(@PathVariable String type, @PathVariable long id) {
        String t = kind(type);
        try {
            Map<String, Object> r = jdbc.queryForMap("SELECT content_type, data FROM item_images WHERE owner_type = ? AND owner_id = ?", t, id);
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType((String) r.get("content_type")))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body((byte[]) r.get("data"));
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No photo.");
        }
    }

    @PutMapping(value = "/api/admin/products/{id}/image", consumes = {"image/jpeg", "image/png", "image/webp"})
    @PreAuthorize("hasAuthority('PERM_products_edit')")
    Map<String, Object> putProduct(@PathVariable long id, @RequestBody byte[] body, @org.springframework.web.bind.annotation.RequestHeader("Content-Type") String type, Principal who) {
        return save("PRODUCT", "products", id, body, type, who);
    }

    @PutMapping(value = "/api/admin/items/{id}/image", consumes = {"image/jpeg", "image/png", "image/webp"})
    @PreAuthorize("hasAuthority('PERM_items_master_edit')")
    Map<String, Object> putItem(@PathVariable long id, @RequestBody byte[] body, @org.springframework.web.bind.annotation.RequestHeader("Content-Type") String type, Principal who) {
        return save("ITEM", "items", id, body, type, who);
    }

    @DeleteMapping("/api/admin/products/{id}/image")
    @PreAuthorize("hasAuthority('PERM_products_edit')")
    Map<String, Object> deleteProduct(@PathVariable long id, Principal who) { return remove("PRODUCT", id, who); }

    @DeleteMapping("/api/admin/items/{id}/image")
    @PreAuthorize("hasAuthority('PERM_items_master_edit')")
    Map<String, Object> deleteItem(@PathVariable long id, Principal who) { return remove("ITEM", id, who); }

    private Map<String, Object> save(String t, String table, long id, byte[] body, String contentType, Principal who) {
        String ct = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
        if (body == null || body.length == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a photo.");
        if (body.length > MAX_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "That photo is too big. Use one under 400 KB.");
        if (!looksLike(ct, body)) throw new ApiException(HttpStatus.BAD_REQUEST, "That file is not a JPEG, PNG or WebP photo.");
        Integer there = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (there == null || there == 0) throw new ApiException(HttpStatus.NOT_FOUND, "That " + (t.equals("ITEM") ? "item" : "product") + " no longer exists.");
        jdbc.update("""
            INSERT INTO item_images(owner_type, owner_id, content_type, data, updated_at, updated_by) VALUES (?, ?, ?, ?, now(), ?)
            ON CONFLICT (owner_type, owner_id) DO UPDATE SET content_type = EXCLUDED.content_type, data = EXCLUDED.data,
                updated_at = now(), updated_by = EXCLUDED.updated_by
            """, t, id, ct, body, who.getName());
        audit.record(who.getName(), null, t + "_IMAGE_SET", t.toLowerCase(), String.valueOf(id), null, "{\"bytes\":" + body.length + "}", null);
        Long ver = jdbc.queryForObject("SELECT (extract(epoch FROM updated_at) * 1000)::bigint FROM item_images WHERE owner_type = ? AND owner_id = ?", Long.class, t, id);
        return Map.of("ver", ver == null ? 0L : ver);
    }

    private Map<String, Object> remove(String t, long id, Principal who) {
        int n = jdbc.update("DELETE FROM item_images WHERE owner_type = ? AND owner_id = ?", t, id);
        if (n > 0) audit.record(who.getName(), null, t + "_IMAGE_REMOVE", t.toLowerCase(), String.valueOf(id), null, null, null);
        return Map.of("removed", n > 0);
    }

    private static String kind(String type) {
        if ("product".equals(type)) return "PRODUCT";
        if ("item".equals(type)) return "ITEM";
        throw new ApiException(HttpStatus.NOT_FOUND, "No photo.");
    }

    /** The file starts the way its type says it should (a renamed document is not accepted). */
    static boolean looksLike(String ct, byte[] b) {
        if (b.length < 12) return false;
        return switch (ct) {
            case "image/jpeg" -> (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
            case "image/png" -> (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "image/webp" -> b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            default -> false;
        };
    }
}
