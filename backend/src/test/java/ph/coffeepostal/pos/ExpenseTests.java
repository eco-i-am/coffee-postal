package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ExpenseTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private ResultActions send(MockHttpSession s, String url, String json) throws Exception {
		return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long id(ResultActions a) throws Exception {
		return ((Number) JsonPath.read(a.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	@Test
	void expensesAreScopedAndVoidedNotDeletedAndProfitAndLossAddsUp() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		long b1 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		long b2 = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-02'", Long.class);
		long cashierId = jdbc.queryForObject("SELECT id FROM users WHERE username='cashier'", Long.class);
		long managerId = jdbc.queryForObject("SELECT id FROM users WHERE username='manager'", Long.class);
		long rent = jdbc.queryForObject("SELECT id FROM expense_categories WHERE name='Rent'", Long.class);
		long util = jdbc.queryForObject("SELECT id FROM expense_categories WHERE name='Utilities'", Long.class);
		String day = "2025-02-10";
		String range = "from=2025-02-10&to=2025-02-10";

		mvc.perform(get("/api/branch/expenses").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/expenses").session(manager)).andExpect(status().isForbidden());

		// rules
		send(manager, "/api/branch/expenses", "{\"categoryId\":" + rent + ",\"amount\":\"0\",\"date\":\"" + day + "\",\"description\":\"x\",\"paidWith\":\"CASH\"}").andExpect(status().isBadRequest());
		send(manager, "/api/branch/expenses", "{\"categoryId\":999999,\"amount\":\"10\",\"date\":\"" + day + "\",\"description\":\"x\",\"paidWith\":\"CASH\"}").andExpect(status().isBadRequest());
		send(manager, "/api/branch/expenses", "{\"categoryId\":" + rent + ",\"amount\":\"10\",\"date\":\"2999-01-01\",\"description\":\"x\",\"paidWith\":\"CASH\"}").andExpect(status().isBadRequest());
		send(manager, "/api/branch/expenses", "{\"categoryId\":" + rent + ",\"amount\":\"10\",\"date\":\"" + day + "\",\"description\":\"x\",\"paidWith\":\"GOLD\"}").andExpect(status().isBadRequest());

		// the branch records its own (a branchId sent by the client is ignored), HQ records one for itself
		long e1 = id(send(manager, "/api/branch/expenses", "{\"categoryId\":" + rent + ",\"amount\":\"200\",\"date\":\"" + day + "\",\"description\":\"Rent\",\"paidWith\":\"BANK\",\"branchId\":" + b2 + "}").andExpect(status().isCreated()));
		assertThat(jdbc.queryForObject("SELECT branch_id FROM expenses WHERE id=?", Long.class, e1)).isEqualTo(b1);
		send(manager, "/api/branch/expenses", "{\"categoryId\":" + util + ",\"amount\":\"50\",\"date\":\"" + day + "\",\"description\":\"Electric\",\"paidWith\":\"CASH\"}").andExpect(status().isCreated());
		long wrong = id(send(manager, "/api/branch/expenses", "{\"categoryId\":" + util + ",\"amount\":\"999.99\",\"date\":\"" + day + "\",\"description\":\"Typo\",\"paidWith\":\"CASH\"}"));
		send(admin, "/api/admin/expenses", "{\"categoryId\":" + util + ",\"amount\":\"70\",\"date\":\"" + day + "\",\"description\":\"HQ internet\",\"paidWith\":\"BANK\"}").andExpect(status().isCreated());

		// a wrong entry is voided with a reason, never deleted; another branch cannot void it
		send(manager, "/api/branch/expenses/" + wrong + "/void", "{\"reason\":\"\"}").andExpect(status().isBadRequest());
		jdbc.update("UPDATE expenses SET branch_id = ? WHERE id = ?", b2, wrong);
		send(manager, "/api/branch/expenses/" + wrong + "/void", "{\"reason\":\"typo\"}").andExpect(status().isNotFound());
		jdbc.update("UPDATE expenses SET branch_id = ? WHERE id = ?", b1, wrong);
		send(manager, "/api/branch/expenses/" + wrong + "/void", "{\"reason\":\"typo\"}").andExpect(status().isOk());
		send(manager, "/api/branch/expenses/" + wrong + "/void", "{\"reason\":\"again\"}").andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses WHERE id=? AND voided_at IS NOT NULL AND void_reason='typo'", Integer.class, wrong)).isEqualTo(1);
		mvc.perform(get("/api/branch/expenses?" + range).session(manager)).andExpect(jsonPath("$.length()").value(3));   // the branch's 3 (2 live + 1 voided), not HQ's
		mvc.perform(get("/api/admin/expenses?" + range).session(admin)).andExpect(jsonPath("$.length()").value(4));

		// sales, stock used, waste, outsource, all dated that day (Manila)
		UUID order = UUID.randomUUID(), refunded = UUID.randomUUID();
		long no = 900_000_000L + System.nanoTime() % 1_000_000L;
		jdbc.update("INSERT INTO orders(id, branch_id, order_no, cashier_id, order_type, subtotal, discount, total, vatable, vat, status, created_at) VALUES (?, ?, ?, ?, 'DINE_IN', 100000, 10000, 90000, 72000, 9000, 'PAID', ?::timestamp AT TIME ZONE 'Asia/Manila')", order, b1, no, cashierId, day + " 10:00:00");
		jdbc.update("INSERT INTO orders(id, branch_id, order_no, cashier_id, order_type, subtotal, discount, total, vatable, vat, status, created_at) VALUES (?, ?, ?, ?, 'DINE_IN', 50000, 0, 50000, 0, 0, 'REFUNDED', ?::timestamp AT TIME ZONE 'Asia/Manila')", refunded, b1, no + 1, cashierId, day + " 11:00:00");
		long item = jdbc.queryForObject("INSERT INTO items(name, unit, cost, active) VALUES (?, 'kg', 10000, FALSE) RETURNING id", Long.class, "PL test " + System.nanoTime());
		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, created_at) VALUES (?, ?, -2500, 'SALE', ?::timestamp AT TIME ZONE 'Asia/Manila')", b1, item, day + " 10:00:00");
		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, created_at) VALUES (?, ?, -500, 'WASTE', ?::timestamp AT TIME ZONE 'Asia/Manila')", b1, item, day + " 12:00:00");
		long purchase = jdbc.queryForObject("INSERT INTO outsource_purchases(branch_id, vendor, purchased_on, paid_with, total, created_by) VALUES (?, 'Palengke', ?::date, 'CASH', 102999, ?) RETURNING id", Long.class, b1, day, managerId);
		jdbc.update("INSERT INTO outsource_lines(purchase_id, item_id, description, qty, unit_cost, amount) VALUES (?, NULL, 'Ice', 1000, 3000, 3000)", purchase);
		jdbc.update("INSERT INTO outsource_lines(purchase_id, item_id, description, qty, unit_cost, amount) VALUES (?, ?, 'Stocked', 1000, 99999, 99999)", purchase, item);

		// net sales 90,000 - 9,000 VAT = 81,000; cost of goods 25,000; waste 5,000 -> gross profit 51,000
		// branch expenses 20,000 + 5,000, outsource not stocked 3,000 -> net 23,000
		mvc.perform(get("/api/branch/reports/pl?" + range).session(manager)).andExpect(status().isOk())
			.andExpect(jsonPath("$.orders").value(1)).andExpect(jsonPath("$.grossSales").value(100000)).andExpect(jsonPath("$.discounts").value(10000))
			.andExpect(jsonPath("$.netSales").value(81000)).andExpect(jsonPath("$.costOfGoods").value(25000)).andExpect(jsonPath("$.waste").value(5000))
			.andExpect(jsonPath("$.grossProfit").value(51000)).andExpect(jsonPath("$.expensesTotal").value(25000)).andExpect(jsonPath("$.outsource").value(3000))
			.andExpect(jsonPath("$.netProfit").value(23000)).andExpect(jsonPath("$.expenses.length()").value(2));
		// HQ: all branches add HQ's own 7,000 -> 16,000; one branch only -> 23,000; branch 2 has nothing
		mvc.perform(get("/api/admin/reports/pl?" + range).session(admin)).andExpect(jsonPath("$.expensesTotal").value(32000)).andExpect(jsonPath("$.netProfit").value(16000));
		mvc.perform(get("/api/admin/reports/pl?" + range + "&branchId=" + b1).session(admin)).andExpect(jsonPath("$.netProfit").value(23000));
		mvc.perform(get("/api/admin/reports/pl?" + range + "&branchId=" + b2).session(admin)).andExpect(jsonPath("$.netProfit").value(0)).andExpect(jsonPath("$.orders").value(0));
		mvc.perform(get("/api/admin/reports/pl?" + range).session(manager)).andExpect(status().isForbidden());

		jdbc.update("DELETE FROM orders WHERE id IN (?, ?)", order, refunded);
	}
}
