package ph.coffeepostal.pos.branch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ph.coffeepostal.pos.shared.ApiException;
import ph.coffeepostal.pos.shared.AuditService;

/** HQ: Branch List (create, open/close). */
@RestController
@RequestMapping("/api/admin/branches")
class BranchAdminApi {
    record BranchDto(long id, String code, String name, String address, String phone, String tin, boolean vatRegistered, boolean active) {
        static BranchDto of(Branch b) { return new BranchDto(b.getId(), b.getCode(), b.getName(), b.getAddress(), b.getPhone(), b.getTin(), b.isVatRegistered(), b.isActive()); }
    }
    record BranchReq(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 120) String name,
                     @Size(max = 300) String address, @Size(max = 40) String phone) {}
    record BranchEditReq(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 120) String name,
                         @Size(max = 300) String address, @Size(max = 40) String phone, @Size(max = 30) String tin, boolean vatRegistered) {}

    private final BranchRepository branches;
    private final AuditService audit;

    BranchAdminApi(BranchRepository branches, AuditService audit) { this.branches = branches; this.audit = audit; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_branches_view')")
    List<BranchDto> list() { return branches.findAllByOrderByNameAsc().stream().map(BranchDto::of).toList(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_branches_add')")
    BranchDto create(@Valid @RequestBody BranchReq r, Principal who) {
        String code = r.code().trim().toUpperCase();
        if (branches.existsByCodeIgnoreCase(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "That branch code is already used.", Map.of("code", "Already used."));
        }
        Branch b = branches.save(new Branch(code, r.name().trim(), r.address(), r.phone()));
        audit.record(who.getName(), b.getId(), "BRANCH_CREATE", "branch", String.valueOf(b.getId()), null,
            "{\"code\":\"" + b.getCode() + "\",\"name\":\"" + b.getName().replace("\"", "'") + "\"}", null);
        return BranchDto.of(b);
    }

    /** Change a branch's details. A new code must not belong to another branch. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_branches_edit')")
    BranchDto update(@PathVariable long id, @Valid @RequestBody BranchEditReq r, Principal who) {
        Branch b = branches.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That branch no longer exists."));
        String code = r.code().trim().toUpperCase();
        if (branches.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "That branch code is already used.", Map.of("code", "Already used."));
        }
        String before = "{\"code\":\"" + b.getCode() + "\",\"name\":\"" + clean(b.getName()) + "\",\"vat\":" + b.isVatRegistered() + "}";
        b.setCode(code);
        b.setName(r.name().trim());
        b.setAddress(blank(r.address()));
        b.setPhone(blank(r.phone()));
        b.setTin(blank(r.tin()));
        b.setVatRegistered(r.vatRegistered());
        branches.save(b);
        audit.record(who.getName(), b.getId(), "BRANCH_UPDATE", "branch", String.valueOf(id), before,
            "{\"code\":\"" + code + "\",\"name\":\"" + clean(b.getName()) + "\",\"vat\":" + b.isVatRegistered() + "}", null);
        return BranchDto.of(b);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String clean(String s) { return s == null ? "" : s.replace("\"", "'"); }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('PERM_branches_edit')")
    BranchDto toggle(@PathVariable long id, Principal who) {
        Branch b = branches.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That branch no longer exists."));
        boolean before = b.isActive();
        b.setActive(!before);
        branches.save(b);
        audit.record(who.getName(), b.getId(), "BRANCH_TOGGLE", "branch", String.valueOf(id),
            "{\"active\":" + before + "}", "{\"active\":" + !before + "}", null);
        return BranchDto.of(b);
    }
}
