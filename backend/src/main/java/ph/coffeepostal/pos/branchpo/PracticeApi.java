package ph.coffeepostal.pos.branchpo;

import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.PracticeContext;
import ph.coffeepostal.pos.shared.PracticeFilter;
import ph.coffeepostal.pos.shared.PracticeSchema;

/**
 * Practice mode for purchase orders: turn it on for this session, and (for branch staff, who have no HQ to approve their
 * pretend orders) let "HQ" take the next step. Everything done in practice mode goes to the practice tables only.
 */
@RestController
@RequestMapping("/api/practice")
class PracticeApi {
    private static final String CAN = "hasAnyAuthority('PERM_branch_po_view', 'PERM_branch_po_receive_view', 'PERM_branch_po_admin_view')";

    private final BranchPoService pos;
    private final OrderService orders;
    private final PracticeSchema schema;
    private final JdbcTemplate jdbc;

    PracticeApi(BranchPoService pos, OrderService orders, PracticeSchema schema, JdbcTemplate jdbc) {
        this.pos = pos;
        this.orders = orders;
        this.schema = schema;
        this.jdbc = jdbc;
    }

    @GetMapping
    Map<String, Object> state() { return Map.of("on", PracticeContext.on()); }

    @PostMapping("/start")
    @PreAuthorize(CAN)
    Map<String, Object> start(HttpSession session) {
        session.setAttribute(PracticeFilter.ATTR, Boolean.TRUE);
        return Map.of("on", true);
    }

    @PostMapping("/stop")
    Map<String, Object> stop(HttpSession session) {
        session.removeAttribute(PracticeFilter.ATTR);
        return Map.of("on", false);
    }

    /** Branch staff empty their own branch's practice orders; HQ empties everyone's. */
    @PostMapping("/reset")
    @PreAuthorize(CAN)
    Map<String, Object> reset(Principal who) {
        requireOn();
        Long branch = jdbc.queryForList("""
            SELECT ub.branch_id FROM users u JOIN user_branches ub ON ub.user_id = u.id WHERE u.username = ? ORDER BY ub.branch_id LIMIT 1
            """, Long.class, who.getName()).stream().findFirst().orElse(null);
        schema.reset(branch);
        return Map.of("reset", true);
    }

    /** The next step HQ would take on one of this branch's practice orders, done for the trainee. */
    @PostMapping("/hq-step/{id}")
    @PreAuthorize("hasAuthority('PERM_branch_po_view')")
    Map<String, Object> hqStep(@PathVariable long id, Principal who) {
        requireOn();
        var me = orders.whoIs(who.getName());
        String status = pos.detail(id, me.branchId()).po().status();
        String note = "Practice: HQ step done for you";
        switch (status) {
            case "REQUESTED" -> pos.approve(id, me.userId(), Map.of());
            case "PENDING" -> pos.advance(id, "TO_PAY", me.userId(), note);
            case "TO_PAY" -> pos.advance(id, "PROCESSING", me.userId(), note);
            case "PROCESSING" -> pos.advance(id, "FOR_PICKUP", me.userId(), note);
            case "FOR_PICKUP" -> pos.advance(id, "IN_TRANSIT", me.userId(), note);
            case "IN_TRANSIT" -> pos.advance(id, "DELIVERED", me.userId(), note);
            default -> throw new ApiException(HttpStatus.CONFLICT, "HQ has nothing more to do on this order. It is up to the branch now.");
        }
        return Map.of("id", id, "status", pos.detail(id, me.branchId()).po().status());
    }

    private static void requireOn() {
        if (!PracticeContext.on()) throw new ApiException(HttpStatus.CONFLICT, "Turn on practice mode first.");
    }
}
