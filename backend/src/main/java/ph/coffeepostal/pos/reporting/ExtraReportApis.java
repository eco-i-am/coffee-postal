package ph.coffeepostal.pos.reporting;

import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;

/** More branch reports: always the signed-in user's own branch. */
@RestController
@RequestMapping("/api/branch/reports")
class BranchExtraReportApi {
    private final ExtraReportService reports;
    private final OrderService orders;

    BranchExtraReportApi(ExtraReportService reports, OrderService orders) { this.reports = reports; this.orders = orders; }

    private long branch(Principal who) { return orders.whoIs(who.getName()).branchId(); }

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    ExtraReportService.Payments payments(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.payments(ReportService.range(from, to), branch(who));
    }

    @GetMapping("/discounts")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    ExtraReportService.Discounts discounts(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.discounts(ReportService.range(from, to), branch(who));
    }

    @GetMapping("/order-types")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ExtraReportService.TypeRow> orderTypes(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.orderTypes(ReportService.range(from, to), branch(who));
    }

    @GetMapping("/cashiers")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ExtraReportService.CashierRow> cashiers(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.cashiers(ReportService.range(from, to), branch(who));
    }

    @GetMapping("/employees")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ExtraReportService.EmployeeRow> employees(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.employees(ReportService.range(from, to), branch(who));
    }

    @GetMapping("/outsource")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ExtraReportService.OutsourceRow> outsource(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.outsource(ReportService.range(from, to), branch(who));
    }

    @GetMapping("/po-payments")
    @PreAuthorize("hasAuthority('PERM_branch_reports_view')")
    List<ExtraReportService.PoPayRow> poPayments(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        return reports.poPayments(ReportService.range(from, to), branch(who));
    }
}

/** A cashier's own report: their sales and their drawer counts, nobody else's. */
@RestController
@RequestMapping("/api/branch")
class MyReportApi {
    private final ExtraReportService reports;
    private final OrderService orders;

    MyReportApi(ExtraReportService reports, OrderService orders) { this.reports = reports; this.orders = orders; }

    @GetMapping("/my-report")
    @PreAuthorize("hasAuthority('PERM_pos_view')")
    ExtraReportService.MyReport mine(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Principal who) {
        var me = orders.whoIs(who.getName());
        return reports.mine(ReportService.range(from, to), me.userId(), me.branchId());
    }
}

/** More HQ reports: all branches, or one when branchId is given. */
@RestController
@RequestMapping("/api/admin/reports")
class AdminExtraReportApi {
    private final ExtraReportService reports;

    AdminExtraReportApi(ExtraReportService reports) { this.reports = reports; }

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    ExtraReportService.Payments payments(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Long branchId) {
        return reports.payments(ReportService.range(from, to), branchId);
    }

    @GetMapping("/discounts")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    ExtraReportService.Discounts discounts(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                           @RequestParam(required = false) Long branchId) {
        return reports.discounts(ReportService.range(from, to), branchId);
    }

    @GetMapping("/order-types")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ExtraReportService.TypeRow> orderTypes(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                @RequestParam(required = false) Long branchId) {
        return reports.orderTypes(ReportService.range(from, to), branchId);
    }

    @GetMapping("/cashiers")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ExtraReportService.CashierRow> cashiers(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                 @RequestParam(required = false) Long branchId) {
        return reports.cashiers(ReportService.range(from, to), branchId);
    }

    @GetMapping("/employees")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ExtraReportService.EmployeeRow> employees(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) Long branchId) {
        return reports.employees(ReportService.range(from, to), branchId);
    }

    @GetMapping("/outsource")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ExtraReportService.OutsourceRow> outsource(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                    @RequestParam(required = false) Long branchId) {
        return reports.outsource(ReportService.range(from, to), branchId);
    }

    @GetMapping("/po-payments")
    @PreAuthorize("hasAuthority('PERM_reports_admin_view')")
    List<ExtraReportService.PoPayRow> poPayments(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                 @RequestParam(required = false) Long branchId) {
        return reports.poPayments(ReportService.range(from, to), branchId);
    }
}
