package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import ph.coffeepostal.pos.shared.PracticeSchema;

/** Practice mode: a pretend purchase order run never reaches the real tables, and cannot change anything else. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PracticeModeTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PracticeSchema schema;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private ResultActions send(MockHttpSession s, String url, String json) throws Exception {
		return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	@Test
	void aPracticeOrderWalksToCompletionWithoutTouchingTheRealTables() throws Exception {
		var manager = login("manager");
		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		long realPos = count("public.branch_pos"), realInvoices = count("public.invoices"), realStock = count("public.stock_movements");

		mvc.perform(get("/api/me").session(manager)).andExpect(jsonPath("$.practice").value(false));
		send(manager, "/api/practice/start", "{}").andExpect(status().isOk());
		mvc.perform(get("/api/me").session(manager)).andExpect(jsonPath("$.practice").value(true));

		String c = send(manager, "/api/branch/purchase-orders", "{\"lines\":[{\"itemId\":" + beans + ",\"qty\":10}]}").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		long po = ((Number) JsonPath.read(c, "$.id")).longValue();
		// "HQ" takes each step: approve, then to pay, processing, for pick-up, in transit, delivered
		for (int i = 0; i < 6; i++) send(manager, "/api/practice/hq-step/" + po, "{}").andExpect(status().isOk());
		send(manager, "/api/practice/hq-step/" + po, "{}").andExpect(status().isConflict());   // nothing more for HQ to do
		send(manager, "/api/branch/purchase-orders/" + po + "/receive", "{\"lines\":[]}").andExpect(status().isOk());
		mvc.perform(get("/api/branch/payables").session(manager)).andExpect(status().isOk()).andExpect(jsonPath("$[0].poId").value(po));

		// it lives in the practice copies only
		assertThat(count("practice.branch_pos")).isGreaterThan(0);
		assertThat(count("practice.invoices")).isGreaterThan(0);
		assertThat(count("practice.stock_movements")).isGreaterThan(0);
		assertThat(count("public.branch_pos")).isEqualTo(realPos);
		assertThat(count("public.invoices")).isEqualTo(realInvoices);
		assertThat(count("public.stock_movements")).isEqualTo(realStock);

		// leaving practice shows the real list again, without the pretend order
		send(manager, "/api/practice/stop", "{}").andExpect(status().isOk());
		mvc.perform(get("/api/branch/purchase-orders").session(manager)).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == " + po + ")]").isEmpty());
		mvc.perform(get("/api/me").session(manager)).andExpect(jsonPath("$.practice").value(false));
	}

	@Test
	void practiceModeRefusesToChangeAnythingOutsideThePurchaseOrderFlow() throws Exception {
		var manager = login("manager");
		send(manager, "/api/practice/start", "{}").andExpect(status().isOk());
		send(manager, "/api/branch/shift/open", "{\"openingCash\":\"1000\"}").andExpect(status().isConflict());
		send(manager, "/api/pos/orders", "{}").andExpect(status().isConflict());
		mvc.perform(get("/api/branch/shift").session(manager)).andExpect(status().isOk());   // reading is fine
	}

	@Test
	void practiceNeedsASessionAndResetOnlyEmptiesTheBranchOwnOrders() throws Exception {
		var manager = login("manager");
		send(manager, "/api/practice/reset", "{}").andExpect(status().isConflict());   // not in practice mode yet
		send(manager, "/api/practice/start", "{}").andExpect(status().isOk());
		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		send(manager, "/api/branch/purchase-orders", "{\"lines\":[{\"itemId\":" + beans + ",\"qty\":1}]}").andExpect(status().isCreated());
		send(manager, "/api/practice/reset", "{}").andExpect(status().isOk());
		long branch = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM practice.branch_pos WHERE branch_id = ?", Long.class, branch)).isZero();
	}

	@Test
	void practiceCopiesMatchTheRealTables() {
		for (String t : PracticeSchema.TABLES) {
			assertThat(schema.shape("practice", t)).as(t).isEqualTo(schema.shape("public", t)).isNotEmpty();
		}
	}
}
