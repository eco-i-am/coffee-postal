package ph.coffeepostal.pos.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HomeController {
    @GetMapping("/login") String login() { return "login"; }

    /** Route by scope: HQ users -> /admin, branch users -> /branch. */
    @GetMapping("/")
    String home(Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_ADMIN"));
        return admin ? "redirect:/admin" : "redirect:/branch";
    }
    @GetMapping("/admin") String admin() { return "admin/dashboard"; }
    @GetMapping("/branch") String branch() { return "branch/dashboard"; }
    @GetMapping("/pos") String pos() { return "pos/index"; }
}
