package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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
class DiscountTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private String order(MockHttpSession s, String discount, String extra, int expected) throws Exception {
		long americano = jdbc.queryForObject("SELECT id FROM products WHERE name='Americano'", Long.class);
		String json = "{\"id\":\"" + UUID.randomUUID() + "\",\"orderType\":\"TAKEOUT\",\"discountType\":\"" + discount + "\","
			+ "\"paymentMethod\":\"GCASH\"," + extra + "\"lines\":[{\"productId\":" + americano + ",\"qty\":2}]}";
		return mvc.perform(post("/api/pos/orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
	}

	@Test
	void hqDefinesDiscountsTheMenuOffersThemAndTheRulesAreEnforced() throws Exception {
		var admin = login("admin");
		var cashier = login("cashier");
		String code = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

		// create: 50% off, a manager PIN is needed once the discount is over 20 pesos
		mvc.perform(post("/api/admin/discounts").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + code + "\",\"name\":\"Half " + code + "\",\"kind\":\"PERCENT\",\"value\":\"50\",\"requiresId\":false,\"approvalOver\":\"20\",\"active\":true}"))
			.andExpect(status().isCreated());
		mvc.perform(post("/api/admin/discounts").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + code + "\",\"name\":\"Other\",\"kind\":\"PERCENT\",\"value\":\"5\",\"requiresId\":false,\"active\":true}"))
			.andExpect(status().isConflict());
		mvc.perform(post("/api/admin/discounts").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"BAD1\",\"name\":\"Bad one\",\"kind\":\"PERCENT\",\"value\":\"150\",\"requiresId\":false,\"active\":true}"))
			.andExpect(status().isBadRequest());

		// the cashier's menu carries it (this is also what the offline copy is built from)
		String menu = mvc.perform(get("/api/pos/menu").session(cashier)).andExpect(status().isOk())
			.andExpect(jsonPath("$.vatRegistered").isBoolean()).andReturn().getResponse().getContentAsString();
		java.util.List<Integer> values = JsonPath.read(menu, "$.discounts[?(@.code=='" + code + "')].value");
		assertThat(values).containsExactly(5000);

		// over the limit: no PIN is refused, a wrong PIN is refused, the manager's PIN passes
		order(cashier, code, "", 403);
		order(cashier, code, "\"managerPin\":\"0000\",", 403);
		String ok = order(cashier, code, "\"managerPin\":\"9999\",", 200);
		assertThat(((Number) JsonPath.read(ok, "$.discount")).longValue()).isEqualTo(((Number) JsonPath.read(ok, "$.subtotal")).longValue() / 2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE discount_type = ? AND discount_approved_by IS NOT NULL AND NOT approval_flag", Integer.class, code)).isEqualTo(1);

		// an order that waited offline cannot ask for a PIN at upload: it is accepted and flagged
		order(cashier, code, "\"soldAt\":\"" + Instant.now().minusSeconds(3600) + "\",", 200);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE discount_type = ? AND approval_flag", Integer.class, code)).isEqualTo(1);

		// Senior/PWD needs an ID number, and the ID is kept on the order
		order(cashier, "SENIOR_PWD", "", 400);
		order(cashier, "SENIOR_PWD", "\"discountIdNo\":\"SC-1234\",", 200);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE discount_type = 'SENIOR_PWD' AND discount_id_no = 'SC-1234'", Integer.class)).isGreaterThanOrEqualTo(1);

		// unknown and switched-off discounts are refused
		order(cashier, "NOPE", "", 400);
		mvc.perform(put("/api/admin/discounts/" + code).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + code + "\",\"name\":\"Half " + code + "\",\"kind\":\"PERCENT\",\"value\":\"50\",\"requiresId\":false,\"approvalOver\":\"20\",\"active\":false}"))
			.andExpect(status().isOk());
		order(cashier, code, "\"managerPin\":\"9999\",", 400);

		// Senior/PWD keeps its legal formula and ID rule even if an edit says otherwise
		mvc.perform(put("/api/admin/discounts/SENIOR_PWD").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"SENIOR_PWD\",\"name\":\"Senior / PWD\",\"kind\":\"PERCENT\",\"value\":\"5\",\"requiresId\":false,\"active\":true}"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT kind || ':' || requires_id FROM discounts WHERE code = 'SENIOR_PWD'", String.class)).isEqualTo("SENIOR_PWD:true");

		// cashiers cannot manage discounts
		mvc.perform(post("/api/admin/discounts").session(cashier).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"X\",\"name\":\"X\",\"kind\":\"FIXED\",\"value\":\"1\",\"requiresId\":false,\"active\":true}"))
			.andExpect(status().isForbidden());

		jdbc.update("DELETE FROM pin_attempts");   // shared database: do not leave a lockout behind for other tests
	}
}
