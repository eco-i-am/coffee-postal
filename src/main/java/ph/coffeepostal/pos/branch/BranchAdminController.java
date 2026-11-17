package ph.coffeepostal.pos.branch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.coffeepostal.pos.shared.AuditService;

/** HQ: Branch List (create, activate/deactivate). */
@Controller
class BranchAdminController {
    private final BranchRepository branches;
    private final AuditService audit;

    BranchAdminController(BranchRepository branches, AuditService audit) {
        this.branches = branches; this.audit = audit;
    }

    public static class BranchForm {
        @NotBlank @Size(max = 20) private String code = "";
        @NotBlank @Size(max = 120) private String name = "";
        private String address = "";
        private String phone = "";
        public String getCode() { return code; }
        public void setCode(String v) { this.code = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getAddress() { return address; }
        public void setAddress(String v) { this.address = v; }
        public String getPhone() { return phone; }
        public void setPhone(String v) { this.phone = v; }
    }

    @GetMapping("/admin/branches")
    @PreAuthorize("hasAuthority('PERM_branches_view')")
    String list(Model m) {
        m.addAttribute("branches", branches.findAllByOrderByNameAsc());
        if (!m.containsAttribute("form")) m.addAttribute("form", new BranchForm());
        return "admin/branches";
    }

    @PostMapping("/admin/branches")
    @PreAuthorize("hasAuthority('PERM_branches_add')")
    String create(@Valid @ModelAttribute("form") BranchForm f, BindingResult br,
                  Principal who, Model m, RedirectAttributes ra) {
        if (!br.hasErrors() && branches.existsByCodeIgnoreCase(f.getCode().trim())) {
            br.rejectValue("code", "dup", "That branch code is already used.");
        }
        if (br.hasErrors()) {
            m.addAttribute("branches", branches.findAllByOrderByNameAsc());
            return "admin/branches";
        }
        Branch b = branches.save(new Branch(f.getCode().trim().toUpperCase(), f.getName().trim(), f.getAddress(), f.getPhone()));
        audit.record(who.getName(), b.getId(), "BRANCH_CREATE", "branch", String.valueOf(b.getId()),
            null, "{\"code\":\"" + b.getCode() + "\",\"name\":\"" + b.getName().replace("\"", "'") + "\"}", null);
        ra.addFlashAttribute("msg", b.getName() + " added.");
        return "redirect:/admin/branches";
    }

    @PostMapping("/admin/branches/{id}/toggle")
    @PreAuthorize("hasAuthority('PERM_branches_edit')")
    String toggle(@PathVariable long id, Principal who, RedirectAttributes ra) {
        Branch b = branches.findById(id).orElseThrow();
        boolean before = b.isActive();
        b.setActive(!before);
        branches.save(b);
        audit.record(who.getName(), b.getId(), "BRANCH_TOGGLE", "branch", String.valueOf(id),
            "{\"active\":" + before + "}", "{\"active\":" + !before + "}", null);
        ra.addFlashAttribute("msg", b.getName() + (b.isActive() ? " is open." : " is closed."));
        return "redirect:/admin/branches";
    }
}
