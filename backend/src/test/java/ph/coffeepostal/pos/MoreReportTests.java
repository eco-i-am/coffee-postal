package ph.coffeepostal.pos;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MoreReportTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	@Test
	void branchPoPoItemAndPriceReportsAddUpAndStayInScope() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		long b1 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		long b2 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-02'", Long.class);
		long who = jdbc.queryForObject("SELECT id FROM users WHERE username='manager'", Long.class);
		long item = jdbc.queryForObject("INSERT INTO items(name, unit, cost, active) VALUES (?, 'box', 5000, FALSE) RETURNING id", Long.class, "RptItem " + System.nanoTime());
		String name = jdbc.queryForObject("SELECT name FROM items WHERE id=?", String.class, item);

		// two orders in Jan 2025: a completed one (ordered 10, approved 8, received 6 at 50.00) and a rejected one (ignored)
		long done = jdbc.queryForObject("INSERT INTO branch_pos(branch_id, status, requested_by, requested_at) VALUES (?, 'COMPLETED', ?, '2025-01-10T02:00:00Z') RETURNING id", Long.class, b1, who);
		jdbc.update("INSERT INTO branch_po_lines(po_id, item_id, name, unit, unit_cost, qty_ordered, qty_approved, qty_received) VALUES (?, ?, ?, 'box', 5000, 10, 8, 6)", done, item, name);
		long rej = jdbc.queryForObject("INSERT INTO branch_pos(branch_id, status, requested_by, requested_at) VALUES (?, 'REJECTED', ?, '2025-01-11T02:00:00Z') RETURNING id", Long.class, b2, who);
		jdbc.update("INSERT INTO branch_po_lines(po_id, item_id, name, unit, unit_cost, qty_ordered) VALUES (?, ?, ?, 'box', 5000, 4)", rej, item, name);
		String range = "from=2025-01-01&to=2025-01-31";

		mvc.perform(get("/api/branch/reports/branch-pos?" + range).session(manager)).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("COMPLETED")).andExpect(jsonPath("$[0].value").value(30000));
		mvc.perform(get("/api/admin/reports/branch-pos?" + range).session(admin)).andExpect(jsonPath("$.length()").value(2));
		mvc.perform(get("/api/admin/reports/branch-pos?" + range + "&branchId=" + b2).session(admin)).andExpect(jsonPath("$.length()").value(1));

		// PO items: the rejected order does not count
		mvc.perform(get("/api/admin/reports/po-items?" + range).session(admin)).andExpect(jsonPath("$[?(@.name=='" + name + "')].ordered").value(10))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].approved").value(8)).andExpect(jsonPath("$[?(@.name=='" + name + "')].received").value(6))
			.andExpect(jsonPath("$[?(@.name=='" + name + "')].value").value(30000));
		mvc.perform(get("/api/branch/reports/po-items?" + range).session(manager)).andExpect(jsonPath("$[?(@.name=='" + name + "')].value").value(30000));
		mvc.perform(get("/api/admin/reports/po-items?" + range + "&branchId=" + b2).session(admin)).andExpect(jsonPath("$[?(@.name=='" + name + "')]").isEmpty());

		// prices: HQ view counts branches that charge differently; a branch sees its own price and availability
		long product = jdbc.queryForObject("SELECT id FROM products WHERE active ORDER BY id LIMIT 1", Long.class);
		long base = jdbc.queryForObject("SELECT price FROM products WHERE id=?", Long.class, product);
		jdbc.update("INSERT INTO branch_products(branch_id, product_id, available, price) VALUES (?, ?, FALSE, ?) ON CONFLICT (branch_id, product_id) DO UPDATE SET available = FALSE, price = EXCLUDED.price", b1, product, base + 500);
		mvc.perform(get("/api/branch/reports/prices").session(manager)).andExpect(jsonPath("$[?(@.productId==" + product + ")].price").value((int) base + 500))
			.andExpect(jsonPath("$[?(@.productId==" + product + ")].available").value(false)).andExpect(jsonPath("$[?(@.productId==" + product + ")].basePrice").value((int) base));
		mvc.perform(get("/api/admin/reports/prices").session(admin)).andExpect(jsonPath("$[?(@.productId==" + product + ")].branchOverrides").value(1));
		mvc.perform(get("/api/branch/reports/prices").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/reports/po-items?" + range).session(manager)).andExpect(status().isForbidden());

		jdbc.update("DELETE FROM branch_products WHERE branch_id = ? AND product_id = ?", b1, product);
		jdbc.update("DELETE FROM branch_pos WHERE id IN (?, ?)", done, rej);
	}
}
