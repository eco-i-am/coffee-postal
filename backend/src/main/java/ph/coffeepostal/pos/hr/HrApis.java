package ph.coffeepostal.pos.hr;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.sales.OrderService;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** HTTP endpoints for the time clock, employees and attendance. Branch comes from the signed-in account, never from the request. */
final class HrApis {
    private HrApis() {}

    record EmployeeReq(@NotBlank @Size(max = 120) String fullName, @Size(max = 80) String position, String pin) {}
    record EmployeeEditReq(@NotBlank @Size(max = 120) String fullName, @Size(max = 80) String position, @NotBlank String status) {}
    record PinReq(@NotBlank String pin) {}
    record AdminEmployeeReq(Long branchId, @NotBlank @Size(max = 120) String fullName, @Size(max = 80) String position, String pin) {}
    record CorrectReq(@NotNull Instant punchedAt, @NotBlank @Size(max = 200) String reason, @NotBlank String managerPin) {}
    record ManualReq(@NotNull Long employeeId, @NotBlank String kind, @NotNull Instant punchedAt, @NotBlank @Size(max = 200) String reason, @NotBlank String managerPin) {}

    static LocalDate day(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return LocalDate.parse(s); }
        catch (DateTimeParseException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Dates look like 2026-11-18."); }
    }

    static LocalDate today() { return LocalDate.now(AttendanceService.MANILA); }

    /** The branch tablet's clock screen. */
    @RestController
    @RequestMapping("/api/clock")
    static class ClockApi {
        private final ClockService clock;
        private final OrderService orders;

        ClockApi(ClockService clock, OrderService orders) { this.clock = clock; this.orders = orders; }

        @GetMapping("/roster")
        @PreAuthorize("hasAuthority('PERM_time_clock_view')")
        ClockService.Roster roster(Principal who) { return clock.roster(orders.whoIs(who.getName()).branchId()); }

        @PostMapping("/punch")
        @PreAuthorize("hasAuthority('PERM_time_clock_add')")
        ClockService.PunchRes punch(@RequestBody ClockService.PunchReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            return clock.punch(me.branchId(), me.userId(), r);
        }
    }

    /** A branch manager's staff list. */
    @RestController
    @RequestMapping("/api/branch/employees")
    static class BranchEmployeeApi {
        private final EmployeeService employees;
        private final OrderService orders;
        private final AuditService audit;

        BranchEmployeeApi(EmployeeService employees, OrderService orders, AuditService audit) { this.employees = employees; this.orders = orders; this.audit = audit; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_branch_employees_view')")
        List<EmployeeService.Employee> list(Principal who) { return employees.list(orders.whoIs(who.getName()).branchId()); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_branch_employees_add')")
        Map<String, Object> add(@Valid @RequestBody EmployeeReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            long id = employees.create(me.branchId(), r.fullName(), r.position(), r.pin());
            audit.record(who.getName(), me.branchId(), "EMPLOYEE_CREATE", "employee", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_branch_employees_edit')")
        Map<String, Object> edit(@PathVariable long id, @Valid @RequestBody EmployeeEditReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            mine(id, me.branchId());
            employees.update(id, r.fullName(), r.position(), r.status());
            audit.record(who.getName(), me.branchId(), "EMPLOYEE_UPDATE", "employee", String.valueOf(id), null, "{\"status\":\"" + r.status() + "\"}", null);
            return Map.of("id", id);
        }

        @PostMapping("/{id}/pin")
        @PreAuthorize("hasAuthority('PERM_branch_employees_edit')")
        Map<String, Object> pin(@PathVariable long id, @Valid @RequestBody PinReq r, Principal who) {
            var me = orders.whoIs(who.getName());
            mine(id, me.branchId());
            employees.setPin(id, me.branchId(), r.pin());
            audit.record(who.getName(), me.branchId(), "EMPLOYEE_PIN_SET", "employee", String.valueOf(id), null, null, null);   // never the PIN itself
            return Map.of("id", id);
        }

        private void mine(long id, long branchId) {
            var e = employees.get(id);
            if (e == null || e.branchId() == null || e.branchId() != branchId) throw new ApiException(HttpStatus.NOT_FOUND, "That employee was not found.");
        }
    }

    /** A branch manager's attendance report and corrections. */
    @RestController
    @RequestMapping("/api/branch/attendance")
    static class BranchAttendanceApi {
        private final AttendanceService attendance;
        private final OrderService orders;

        BranchAttendanceApi(AttendanceService attendance, OrderService orders) { this.attendance = attendance; this.orders = orders; }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_branch_employees_view')")
        AttendanceService.Report report(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                        @RequestParam(required = false) Long employeeId, Principal who) {
            LocalDate end = day(to, today());
            return attendance.report(orders.whoIs(who.getName()).branchId(), employeeId, day(from, end.minusDays(6)), end);
        }

        @PutMapping("/{logId}")
        @PreAuthorize("hasAuthority('PERM_branch_employees_edit')")
        Map<String, Object> correct(@PathVariable long logId, @Valid @RequestBody CorrectReq r, Principal who) {
            attendance.correct(orders.whoIs(who.getName()), who.getName(), logId, r.punchedAt(), r.reason(), r.managerPin());
            return Map.of("id", logId);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_branch_employees_edit')")
        Map<String, Object> add(@Valid @RequestBody ManualReq r, Principal who) {
            long id = attendance.addManual(orders.whoIs(who.getName()), who.getName(), r.employeeId(), r.kind(), r.punchedAt(), r.reason(), r.managerPin());
            return Map.of("id", id);
        }
    }

    /** HQ: every employee, HQ's own staff, and attendance across branches (read only). */
    @RestController
    @RequestMapping("/api/admin")
    static class AdminHrApi {
        private final EmployeeService employees;
        private final AttendanceService attendance;
        private final AuditService audit;

        AdminHrApi(EmployeeService employees, AttendanceService attendance, AuditService audit) { this.employees = employees; this.attendance = attendance; this.audit = audit; }

        @GetMapping("/employees")
        @PreAuthorize("hasAuthority('PERM_admin_employees_view')")
        List<EmployeeService.Employee> list(@RequestParam(required = false) Long branchId) { return employees.list(branchId); }

        @PostMapping("/employees")
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_admin_employees_add')")
        Map<String, Object> add(@Valid @RequestBody AdminEmployeeReq r, Principal who) {
            long id = employees.create(r.branchId(), r.fullName(), r.position(), r.branchId() == null ? null : r.pin());
            audit.record(who.getName(), r.branchId(), "EMPLOYEE_CREATE", "employee", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @PutMapping("/employees/{id}")
        @PreAuthorize("hasAuthority('PERM_admin_employees_edit')")
        Map<String, Object> edit(@PathVariable long id, @Valid @RequestBody EmployeeEditReq r, Principal who) {
            employees.update(id, r.fullName(), r.position(), r.status());
            audit.record(who.getName(), null, "EMPLOYEE_UPDATE", "employee", String.valueOf(id), null, "{\"status\":\"" + r.status() + "\"}", null);
            return Map.of("id", id);
        }

        @PostMapping("/employees/{id}/pin")
        @PreAuthorize("hasAuthority('PERM_admin_employees_edit')")
        Map<String, Object> pin(@PathVariable long id, @Valid @RequestBody PinReq r, Principal who) {
            var e = employees.get(id);
            if (e == null) throw new ApiException(HttpStatus.NOT_FOUND, "That employee was not found.");
            if (e.branchId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "HQ staff do not use the branch time clock.");
            employees.setPin(id, e.branchId(), r.pin());
            audit.record(who.getName(), e.branchId(), "EMPLOYEE_PIN_SET", "employee", String.valueOf(id), null, null, null);
            return Map.of("id", id);
        }

        @GetMapping("/attendance")
        @PreAuthorize("hasAuthority('PERM_admin_employees_view')")
        AttendanceService.Report report(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to, @RequestParam(required = false) Long employeeId) {
            LocalDate end = day(to, today());
            return attendance.report(branchId, employeeId, day(from, end.minusDays(6)), end);
        }
    }
}
