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
        if (seedDemo && !demoPassword.isBlank()) { seedDemo(); seedCatalog(); seedInventory(); }
    }

    /** Sample stock items and opening stock for the demo branches (dev only). */
    private void seedInventory() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM items", Integer.class);
        if (n != null && n > 0) return;
        Object[][] items = {
            {"Espresso beans (1 kg bag)", "bag", 85000, 4}, {"Fresh milk (1 L)", "carton", 9500, 20},
            {"Oat milk (1 L)", "carton", 18000, 6}, {"Caramel syrup (750 ml)", "bottle", 52000, 3},
            {"Cups 12 oz (50 pcs)", "pack", 18000, 5}, {"Lids 12 oz (50 pcs)", "pack", 9000, 5},
            {"Straws (100 pcs)", "pack", 4500, 4}, {"Butter croissant (frozen, 12 pcs)", "box", 96000, 2}};
        for (Object[] i : items) {
            jdbc.update("INSERT INTO items(name, unit, cost, reorder_level) VALUES (?, ?, ?, ?)", i[0], i[1], i[2], i[3]);
        }
        // opening stock: plenty of most things, the caramel syrup deliberately low so "low stock" shows up
        jdbc.update("""
            INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason)
            SELECT b.id, i.id, CASE WHEN i.name LIKE 'Caramel%' THEN 2000 ELSE i.reorder_level * 3000 END, 'OPENING', 'Opening stock'
            FROM branches b CROSS JOIN items i WHERE b.code IN ('CEB-01','CEB-02')
            """);
        // recipes (thousandths of the item's unit per drink): 18 g of a 1 kg bag = 18, 200 ml of a 1 L carton = 200, and so on
        Object[][] recipes = {
            {"Americano", "Espresso%", 18}, {"Americano", "Cups 12%", 20}, {"Americano", "Lids 12%", 20},
            {"Cafe Latte", "Espresso%", 18}, {"Cafe Latte", "Fresh milk%", 200}, {"Cafe Latte", "Cups 12%", 20}, {"Cafe Latte", "Lids 12%", 20}};
        for (Object[] r : recipes) {
            jdbc.update("""
                INSERT INTO product_recipes(product_id, item_id, qty)
                SELECT p.id, i.id, ? FROM products p, items i WHERE p.name = ? AND i.name LIKE ?
                """, r[2], r[0], r[1]);
        }
        log.info("Seeded demo inventory (dev only)");
    }

    /** Sample menu so the POS has something to sell in dev. The real menu is entered by HQ. */
    private void seedCatalog() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
        if (n != null && n > 0) return;
        jdbc.update("INSERT INTO product_categories(name, sort_order) VALUES ('Coffee',1),('Frappe',2),('Non-Coffee',3),('Food',4)");
        jdbc.update("INSERT INTO addons(name, price) VALUES ('Extra shot',2500),('Syrup',1500),('Oat milk',3000),('Whipped cream',1500)");
        Object[][] menu = {
            {"Coffee","Americano",7900,"hot","#FFC74A",true},{"Coffee","Cafe Latte",9900,"hot","#FFA076",true},
            {"Coffee","Spanish Latte",11900,"hot","#FDCDC1",true},{"Coffee","Caramel Macchiato",11900,"iced","#FFC74A",true},
            {"Coffee","Mocha",10900,"hot","#FDCDC1",true},{"Coffee","Postal Blend Iced Coffee",8900,"iced","#8DCCC0",true},
            {"Frappe","Java Chip Frappe",13900,"frap","#8DCCC0",true},{"Frappe","Caramel Frappe",13900,"frap","#FFC74A",true},
            {"Non-Coffee","Matcha Latte",11900,"iced","#8DCCC0",true},{"Non-Coffee","Hot Chocolate",9900,"hot","#FFA076",true},
            {"Food","Butter Croissant",8500,"pastry","#FDCDC1",false},{"Food","Choco Chip Cookie",6500,"cookie","#8DCCC0",false},
            {"Food","Ham & Cheese Sandwich",9900,"sand","#FFC74A",false}};
        for (Object[] m : menu) {
            long id = jdbc.queryForObject("""
                INSERT INTO products(category_id, name, price, art, color, has_sizes)
                VALUES ((SELECT id FROM product_categories WHERE name = ?), ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, m[0], m[1], m[2], m[3], m[4], m[5]);
            if ((boolean) m[5]) {
                jdbc.update("INSERT INTO product_variants(product_id, name, price_delta, sort_order) VALUES (?, 'Regular', 0, 0), (?, 'Large', 2000, 1)", id, id);
                jdbc.update("INSERT INTO product_addons(product_id, addon_id) SELECT ?, id FROM addons", id);
            }
        }
        log.info("Seeded demo menu (dev only)");
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
