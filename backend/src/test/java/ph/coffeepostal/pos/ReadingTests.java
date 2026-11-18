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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReadingTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private String sell(MockHttpSession s, String method, String discount, String extra) throws Exception {
		long p = jdbc.queryForObject("SELECT id FROM products WHERE name='Americano'", Long.class);
		String id = UUID.randomUUID().toString();
		String json = "{\"id\":\"" + id + "\",\"orderType\":\"DINE_IN\",\"discountType\":\"" + discount + "\",\"paymentMethod\":\"" + method + "\","
			+ ("CASH".equals(method) ? "\"tendered\":10000," : "") + extra + "\"lines\":[{\"productId\":" + p + ",\"qty\":1}]}";
		mvc.perform(post("/api/pos/orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
		return id;
	}

	private void open(MockHttpSession s) throws Exception {
		mvc.perform(post("/api/branch/shift/open").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingCash\":\"500\"}")).andExpect(status().isOk());
	}

	private int close(MockHttpSession s, String counted) throws Exception {
		String body = mvc.perform(post("/api/branch/shift/close").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"countedCash\":\"" + counted + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.zNo")).intValue();
	}

	@Test
	void xReadingShowsTheOpenShiftZReadingGetsARunningNumberAndReprintsAreCounted() throws Exception {
		var cashier = login("cashier");
		var manager = login("manager");
		jdbc.update("UPDATE shifts SET closed_at = now(), expected_cash = 0, counted_cash = 0 WHERE closed_at IS NULL");

		mvc.perform(get("/api/branch/shift/reading").session(cashier)).andExpect(status().isConflict());   // no shift yet

		open(cashier);
		String cashOrder = sell(cashier, "CASH", "NONE", "");
		sell(cashier, "GCASH", "SENIOR_PWD", "\"discountIdNo\":\"SC-77\",");
		long shiftId = jdbc.queryForObject("SELECT id FROM shifts WHERE closed_at IS NULL", Long.class);

		// X reading: nothing changes, discounts and payments are broken out
		mvc.perform(get("/api/branch/shift/reading").session(cashier))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("X"))
			.andExpect(jsonPath("$.orders").value(2))
			.andExpect(jsonPath("$.payments.length()").value(2))
			.andExpect(jsonPath("$.discountLines[0].label").value("Senior / PWD"))
			.andExpect(jsonPath("$.openingCash").value(50000))
			.andExpect(jsonPath("$.cashSales").value(7900))
			.andExpect(jsonPath("$.expected").value(57900));
		assertThat(jdbc.queryForObject("SELECT closed_at IS NULL FROM shifts WHERE id = ?", Boolean.class, shiftId)).isTrue();

		// receipt reprint: counted, audited, scoped to the branch
		mvc.perform(post("/api/branch/orders/" + cashOrder + "/reprint").session(cashier).with(csrf()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.reprintCount").value(1)).andExpect(jsonPath("$.lines[0].name").value("Americano"));
		mvc.perform(post("/api/branch/orders/" + cashOrder + "/reprint").session(cashier).with(csrf()))
			.andExpect(jsonPath("$.reprintCount").value(2));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'RECEIPT_REPRINT' AND entity_id = ?", Integer.class, cashOrder)).isEqualTo(2);
		mvc.perform(post("/api/branch/orders/" + UUID.randomUUID() + "/reprint").session(cashier).with(csrf())).andExpect(status().isNotFound());

		// close: the Z number is given, and the Z reading matches
		int z1 = close(cashier, "579");
		mvc.perform(get("/api/branch/shifts/" + shiftId + "/reading").session(cashier))
			.andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("Z")).andExpect(jsonPath("$.zNo").value(z1))
			.andExpect(jsonPath("$.variance").value(0)).andExpect(jsonPath("$.counted").value(57900));
		mvc.perform(get("/api/branch/shifts/" + shiftId + "/reading").session(manager)).andExpect(status().isOk());   // managers see any shift in the branch

		// the next close in the same branch gets the next Z number
		open(cashier);
		assertThat(close(cashier, "500")).isEqualTo(z1 + 1);

		mvc.perform(get("/api/branch/shifts/999999/reading").session(manager)).andExpect(status().isNotFound());
	}
}
