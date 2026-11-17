package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RefundShiftTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private String sellAmericanoCash(MockHttpSession s, long tendered) throws Exception {
		long p = jdbc.queryForObject("SELECT id FROM products WHERE name='Americano'", Long.class);
		String id = UUID.randomUUID().toString();
		String json = "{\"id\":\"" + id + "\",\"orderType\":\"DINE_IN\",\"discountType\":\"NONE\",\"paymentMethod\":\"CASH\","
			+ "\"tendered\":" + tendered + ",\"lines\":[{\"productId\":" + p + ",\"qty\":1}]}";
		mvc.perform(post("/api/pos/orders").session(s).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
		return id;
	}

	private void refund(MockHttpSession s, String id, String pin, int expected) throws Exception {
		mvc.perform(post("/api/branch/orders/" + id + "/refund").session(s).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong drink\",\"pin\":\"" + pin + "\"}"))
			.andExpect(status().is(expected));
	}

	@Test
	void refundNeedsAManagerPinAndOnlyWorksOnce() throws Exception {
		var cashier = login("cashier");
		String id = sellAmericanoCash(cashier, 10000);

		refund(cashier, id, "1234", 403);        // the cashier's own PIN is not a manager PIN
		refund(cashier, id, "9999", 200);        // the manager's PIN approves it
		refund(cashier, id, "9999", 409);        // already refunded

		assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?::uuid", String.class, id)).isEqualTo("REFUNDED");
		mvc.perform(get("/api/branch/orders/" + id).session(cashier))
			.andExpect(jsonPath("$.order.status").value("REFUNDED"))
			.andExpect(jsonPath("$.refund.reason").value("Wrong drink"))
			.andExpect(jsonPath("$.refund.approvedBy").value("Demo Manager"));
	}

	@Test
	void repeatedWrongPinsLockRefundsForTheBranch() throws Exception {
		var cashier = login("cashier");
		String id = sellAmericanoCash(cashier, 10000);
		jdbc.update("DELETE FROM pin_attempts");
		for (int i = 0; i < 5; i++) refund(cashier, id, "0000", 403);
		refund(cashier, id, "9999", 429);        // even the right PIN is refused while locked
		jdbc.update("DELETE FROM pin_attempts");
		refund(cashier, id, "9999", 200);
	}

	@Test
	void shiftAddsUpCashSalesInAndOutAndShowsVariance() throws Exception {
		var cashier = login("cashier");
		jdbc.update("UPDATE shifts SET closed_at = now(), expected_cash = 0, counted_cash = 0 WHERE closed_at IS NULL");

		// no shift yet
		mvc.perform(get("/api/branch/shift").session(cashier)).andExpect(jsonPath("$.shift").doesNotExist());
		mvc.perform(post("/api/branch/shift/cash").session(cashier).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"IN\",\"amount\":\"100\",\"reason\":\"x\"}")).andExpect(status().isConflict());

		mvc.perform(post("/api/branch/shift/open").session(cashier).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"openingCash\":\"1000\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/branch/shift/open").session(cashier).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"openingCash\":\"1000\"}")).andExpect(status().isConflict());

		sellAmericanoCash(cashier, 10000);       // Americano is 79.00, paid in cash
		mvc.perform(post("/api/branch/shift/cash").session(cashier).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"OUT\",\"amount\":\"200\",\"reason\":\"Ice run\"}")).andExpect(status().isOk());
		// cannot take out more than the drawer holds (1000 + 79 - 200 = 879)
		mvc.perform(post("/api/branch/shift/cash").session(cashier).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"OUT\",\"amount\":\"5000\",\"reason\":\"too much\"}")).andExpect(status().isConflict());

		mvc.perform(get("/api/branch/shift").session(cashier))
			.andExpect(jsonPath("$.shift.cashSales").value(7900))
			.andExpect(jsonPath("$.shift.cashOut").value(20000))
			.andExpect(jsonPath("$.shift.expected").value(87900));

		// counted PHP 870.00: PHP 9.00 short
		mvc.perform(post("/api/branch/shift/close").session(cashier).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":\"870\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expected").value(87900))
			.andExpect(jsonPath("$.variance").value(-900));

		// cashiers do not get the shift history; managers do
		mvc.perform(get("/api/branch/shifts").session(cashier)).andExpect(status().isForbidden());
		mvc.perform(get("/api/branch/shifts").session(login("manager"))).andExpect(status().isOk());
	}
}
