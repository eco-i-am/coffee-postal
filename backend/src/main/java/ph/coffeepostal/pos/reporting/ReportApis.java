package ph.coffeepostal.pos.reporting;

import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;

/** Branch reports: always the signed-in user's own branch. */
@RestController
@RequestMapping("/api/branch/reports")
class BranchReportApi {
    private final ReportService reports;
    private final OrderService orders;

    BranchReportApi(ReportService reports, OrderService orders) { this.reports = reports; this.orders = orders; }

    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    ReportService.Sales sales(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.sales(ReportService.range(from, to), orders.whoIs(who.getName()).branchId());
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ReportService.ProductRow> products(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.products(ReportService.range(from, to), orders.whoIs(who.getName()).branchId());
    }

    @GetMapping("/prices")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ReportService.PriceRow> prices(Principal who) { return reports.prices(orders.whoIs(who.getName()).branchId()); }

    @GetMapping("/branch-pos")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ReportService.PoRow> branchPos(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.branchPos(ReportService.range(from, to), orders.whoIs(who.getName()).branchId());
    }

    @GetMapping("/po-items")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ReportService.PoItemRow> poItems(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.poItems(ReportService.range(from, to), orders.whoIs(who.getName()).branchId());
    }

    @GetMapping("/pl")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    ReportService.ProfitLoss pl(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.profitLoss(ReportService.range(from, to), orders.whoIs(who.getName()).branchId());
    }

    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ReportService.StockRow> stock(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.stock(ReportService.range(from, to), orders.whoIs(who.getName()).branchId());
    }
}

/** HQ reports: all branches, or one when branchId is given. */
@RestController
@RequestMapping("/api/admin/reports")
class AdminReportApi {
    private final ReportService reports;

    AdminReportApi(ReportService reports) { this.reports = reports; }

    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    ReportService.Sales sales(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                              @RequestParam(required = false) Long branchId) {
        return reports.sales(ReportService.range(from, to), branchId);
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ReportService.ProductRow> products(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                            @RequestParam(required = false) Long branchId) {
        return reports.products(ReportService.range(from, to), branchId);
    }

    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ReportService.BranchRow> branches(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return reports.branches(ReportService.range(from, to));
    }

    @GetMapping("/prices")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ReportService.PriceRow> prices(@RequestParam(required = false) Long branchId) { return reports.prices(branchId); }

    @GetMapping("/branch-pos")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ReportService.PoRow> branchPos(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                        @RequestParam(required = false) Long branchId) {
        return reports.branchPos(ReportService.range(from, to), branchId);
    }

    @GetMapping("/po-items")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ReportService.PoItemRow> poItems(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                          @RequestParam(required = false) Long branchId) {
        return reports.poItems(ReportService.range(from, to), branchId);
    }

    @GetMapping("/pl")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    ReportService.ProfitLoss pl(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                @RequestParam(required = false) Long branchId) {
        return reports.profitLoss(ReportService.range(from, to), branchId);
    }

    @GetMapping("/aging")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ReportService.AgingRow> aging() { return reports.aging(); }

    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ReportService.StockRow> stock(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                       @RequestParam(required = false) Long branchId) {
        return reports.stock(ReportService.range(from, to), branchId);
    }
}
