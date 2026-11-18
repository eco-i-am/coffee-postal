package ph.coffeepostal.pos.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;
import ph.coffeepostal.pos.shared.PesoInput;

/** HQ: the discounts the POS offers. Senior/PWD follows the law and can only be switched on or off, renamed or given a limit. */
@RestController
@RequestMapping("/api/admin/discounts")
class DiscountAdminApi {
    record Row(String code, String name, String kind, long value, boolean requiresId, Long approvalOver, boolean active) {}
    /** value: a percent (e.g. "15") for PERCENT, pesos (e.g. "50") for FIXED. approvalOver: pesos, empty for no limit. */
    record Req(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_]+", message = "Letters, numbers and _ only") String code,
               @NotBlank @Size(max = 80) String name, @NotBlank String kind, String value,
               boolean requiresId, String approvalOver, boolean active) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    DiscountAdminApi(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_discounts_view')")
    List<Row> list() {
        return jdbc.query("SELECT code, name, kind, value, requires_id, approval_over, active FROM discounts ORDER BY sort_order, name",
            (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getBoolean(5), (Long) rs.getObject(6), rs.getBoolean(7)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_discounts_add')")
    Map<String, Object> add(@Valid @RequestBody Req r, Principal who) {
        String code = r.code().trim().toUpperCase();
        if ("NONE".equals(code)) throw new ApiException(HttpStatus.BAD_REQUEST, "That code is reserved.", Map.of("code", "Pick another."));
        if (!"PERCENT".equals(r.kind()) && !"FIXED".equals(r.kind())) throw new ApiException(HttpStatus.BAD_REQUEST, "Pick percent or fixed amount.", Map.of("kind", "Pick one."));
        long value = value(r);
        Long over = over(r);
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM discounts WHERE code = ? OR lower(name) = lower(?)", Integer.class, code, r.name().trim());
        if (taken != null && taken > 0) throw new ApiException(HttpStatus.CONFLICT, "A discount with that code or name already exists.", Map.of("code", "Already used."));
        jdbc.update("""
            INSERT INTO discounts(code, name, kind, value, requires_id, approval_over, active, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?, (SELECT coalesce(max(sort_order), 0) + 1 FROM discounts))
            """, code, r.name().trim(), r.kind(), value, r.requiresId(), over, r.active());
        audit.record(who.getName(), null, "DISCOUNT_CREATE", "discount", code, null, json(r.kind(), value, over, r.active()), null);
        return Map.of("code", code);
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('PERM_discounts_edit')")
    Map<String, Object> edit(@PathVariable String code, @Valid @RequestBody Req r, Principal who) {
        List<Row> before = jdbc.query("SELECT code, name, kind, value, requires_id, approval_over, active FROM discounts WHERE code = ?",
            (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getBoolean(5), (Long) rs.getObject(6), rs.getBoolean(7)), code);
        if (before.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "That discount no longer exists.");
        Row b = before.get(0);
        boolean senior = "SENIOR_PWD".equals(b.kind());
        // the kind never changes; Senior/PWD keeps its legal formula and its ID requirement
        long value = senior ? 0 : value(r);
        boolean requiresId = senior || r.requiresId();
        Long over = over(r);
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM discounts WHERE lower(name) = lower(?) AND code <> ?", Integer.class, r.name().trim(), code);
        if (taken != null && taken > 0) throw new ApiException(HttpStatus.CONFLICT, "A discount with that name already exists.", Map.of("name", "Already used."));
        jdbc.update("UPDATE discounts SET name = ?, value = ?, requires_id = ?, approval_over = ?, active = ? WHERE code = ?",
            r.name().trim(), value, requiresId, over, r.active(), code);
        audit.record(who.getName(), null, "DISCOUNT_UPDATE", "discount", code, json(b.kind(), b.value(), b.approvalOver(), b.active()),
            json(b.kind(), value, over, r.active()), null);
        return Map.of("code", code);
    }

    private static long value(Req r) {
        if ("PERCENT".equals(r.kind())) {
            BigDecimal p;
            try { p = new BigDecimal(r.value() == null ? "" : r.value().trim()); }
            catch (NumberFormatException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a percent like 10 or 12.5.", Map.of("value", "Enter a percent.")); }
            if (p.signum() <= 0 || p.compareTo(new BigDecimal("100")) > 0 || p.scale() > 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The percent must be above 0 and at most 100, with up to 2 decimals.", Map.of("value", "0 to 100."));
            }
            return p.movePointRight(2).longValueExact();
        }
        return PesoInput.parse(r.value(), false, "value", "Enter an amount like 50 or 50.50.");
    }

    private static Long over(Req r) {
        return r.approvalOver() == null || r.approvalOver().isBlank() ? null
            : PesoInput.parse(r.approvalOver(), true, "approvalOver", "Enter pesos like 100, or leave empty for no limit.");
    }

    private static String json(String kind, long value, Long over, boolean active) {
        return "{\"kind\":\"" + kind + "\",\"value\":" + value + ",\"approvalOver\":" + over + ",\"active\":" + active + "}";
    }
}
