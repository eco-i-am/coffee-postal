package ph.coffeepostal.pos.iam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * First-run setup.
 *  - Creates the Super Admin only if there are no users yet, and only if a password is supplied
 *    (APP_BOOTSTRAP_ADMIN_PASSWORD). In production there is no default password.
 *  - With app.seed-demo=true (dev profile only) also creates demo branches, a manager and a cashier.
 */
@Component
class BootstrapData implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapData.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String adminUser;
    private final String adminPassword;
    private final boolean seedDemo;
    private final String demoPassword;

    BootstrapData(JdbcTemplate jdbc, PasswordEncoder encoder,
                  @Value("${app.bootstrap.admin-username:admin}") String adminUser,
                  @Value("${app.bootstrap.admin-password:}") String adminPassword,
                  @Value("${app.seed-demo:false}") boolean seedDemo,
                  @Value("${app.seed-demo-password:}") String demoPassword) {
        this.jdbc = jdbc; this.encoder = encoder; this.adminUser = adminUser;
        this.adminPassword = adminPassword; this.seedDemo = seedDemo; this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer users = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        if (users != null && users == 0) {
            if (adminPassword.isBlank()) {
                log.warn("No users exist and APP_BOOTSTRAP_ADMIN_PASSWORD is not set. Nobody can sign in until it is set.");
            } else {
                createUser(adminUser, "Super Admin", "Head Office Admin", adminPassword, null, null);
                log.info("Created Super Admin '{}'", adminUser);
            }
        }
        if (seedDemo && !demoPassword.isBlank()) seedDemo();
    }

    private void seedDemo() {
        Integer b = jdbc.queryForObject("SELECT count(*) FROM branches", Integer.class);
        if (b != null && b > 0) return;
        jdbc.update("INSERT INTO branches(code, name, address) VALUES ('CEB-01','Cebu City','Cebu City'),('CEB-02','Mandaue','Mandaue City')");
        long branch1 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
        createUser("manager", "Branch Manager", "Demo Manager", demoPassword, null, branch1);
        createUser("cashier", "Cashier", "Demo Cashier", demoPassword, "1234", branch1);
        log.info("Seeded demo branches and users (dev only)");
    }

    private void createUser(String username, String role, String fullName, String password, String pin, Long branchId) {
        jdbc.update("""
            INSERT INTO users(username, full_name, password_hash, pin_hash, role_id)
            VALUES (?, ?, ?, ?, (SELECT id FROM roles WHERE name = ?))
            """, username, fullName, encoder.encode(password), pin == null ? null : encoder.encode(pin), role);
        if (branchId != null) {
            jdbc.update("INSERT INTO user_branches(user_id, branch_id) VALUES ((SELECT id FROM users WHERE username = ?), ?)", username, branchId);
        }
    }
}
