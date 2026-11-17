package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class ReportTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private long branch;

	/** Fixed data in March 2020 for the Mandaue branch, which no other test touches. */
	@BeforeEach
	void data() {
		branch = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-02'", Long.class);
		if (jdbc.queryForObject("SELECT count(*) FROM orders WHERE branch_id=? AND order_no >= 9001", Integer.class, branch) > 0) return;
		long cashier = jdbc.queryForObject("SELECT id FROM users WHERE username='cashier'", Long.class);
		long product = jdbc.queryForObject("SELECT id FROM products WHERE name='Americano'", Long.class);
		order(9001, cashier, "PAID", "NONE", 11200, 0, 11200, 10000, 1200, "CASH", "Americano", 5600, 2, product);
		order(9002, cashier, "PAID", "SENIOR_PWD", 11200, 3200, 8000, 0, 0, "GCASH", "Americano", 11200, 1, product);
		order(9003, cashier, "REFUNDED", "NONE", 5000, 0, 5000, 4464, 536, "CASH", "Americano", 5000, 1, product);
	}

	private void order(int no, long cashier, String status, String disc, long subtotal, long discount, long total, long vatable, long vat,
	                   String method, String name, long unit, int qty, long product) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO orders(id, branch_id, order_no, cashier_id, order_type, discount_type, subtotal, discount, total, vatable, vat, status, created_at)
			VALUES (?, ?, ?, ?, 'DINE_IN', ?, ?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2020-03-05 09:00:00+08')
			""", id, branch, no, cashier, disc, subtotal, discount, total, vatable, vat, status);
		jdbc.update("INSERT INTO order_lines(order_id, product_id, name, unit_price, qty) VALUES (?, ?, ?, ?, ?)", id, product, name, unit, qty);
		jdbc.update("INSERT INTO order_payments(order_id, method, amount, tendered) VALUES (?, ?, ?, ?)", id, method, total, total);
	}

	@Test
	void salesReportAddsUpPaidOrdersAndKeepsRefundsSeparate() throws Exception {
		mvc.perform(get("/api/admin/reports/sales?from=2020-03-04&to=2020-03-06&branchId=" + branch).session(login("admin")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.days.length()").value(3))                       // empty days are filled in
			.andExpect(jsonPath("$.days[0].sales").value(0))
			.andExpect(jsonPath("$.days[1].date").value("2020-03-05"))
			.andExpect(jsonPath("$.days[1].orders").value(2))
			.andExpect(jsonPath("$.totals.sales").value(19200))
			.andExpect(jsonPath("$.totals.avgOrder").value(9600))
			.andExpect(jsonPath("$.totals.discount").value(3200))
			.andExpect(jsonPath("$.totals.vat").value(1200))
			.andExpect(jsonPath("$.totals.refunds").value(1))
			.andExpect(jsonPath("$.totals.refunded").value(5000))
			.andExpect(jsonPath("$.byPayment[?(@.method=='CASH')].amount").value(11200))   // refunded cash order not counted
			.andExpect(jsonPath("$.byPayment[?(@.method=='GCASH')].amount").value(8000))
			.andExpect(jsonPath("$.discounts[0].type").value("SENIOR_PWD"))
			.andExpect(jsonPath("$.byCashier[0].refunds").value(1));
	}

	@Test
	void productReportCountsOnlyPaidOrders() throws Exception {
		mvc.perform(get("/api/admin/reports/products?from=2020-03-05&to=2020-03-05&branchId=" + branch).session(login("admin")))
			.andExpect(jsonPath("$[0].name").value("Americano"))
			.andExpect(jsonPath("$[0].qty").value(3))
			.andExpect(jsonPath("$[0].sales").value(22400));
	}

	@Test
	void branchComparisonStockAndAgingAreAvailableToHq() throws Exception {
		var admin = login("admin");
		mvc.perform(get("/api/admin/reports/branches?from=2020-03-05&to=2020-03-05").session(admin))
			.andExpect(jsonPath("$[?(@.branchId==" + branch + ")].sales").value(19200));
		mvc.perform(get("/api/admin/reports/stock?branchId=" + branch).session(admin)).andExpect(status().isOk());
		mvc.perform(get("/api/admin/reports/aging").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
	}

	@Test
	void badRangesAreRefusedAndPermissionsHold() throws Exception {
		var admin = login("admin");
		mvc.perform(get("/api/admin/reports/sales?from=2020-03-06&to=2020-03-05").session(admin)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/admin/reports/sales?from=2015-01-01&to=2020-03-05").session(admin)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/admin/reports/sales?from=nope").session(admin)).andExpect(status().isBadRequest());
		// cashiers get no reports; a manager gets only their own branch's, and no HQ ones
		mvc.perform(get("/api/branch/reports/sales").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/branch/reports/sales").session(login("manager"))).andExpect(status().isOk());
		mvc.perform(get("/api/branch/reports/stock").session(login("manager"))).andExpect(status().isOk());
		mvc.perform(get("/api/admin/reports/sales").session(login("manager"))).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE order_no >= 9001", Integer.class)).isGreaterThanOrEqualTo(3);
	}
}
