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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class InventoryPoTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private long item(String like) {
		return jdbc.queryForObject("SELECT id FROM items WHERE name LIKE ?", Long.class, like);
	}

	private long stock(long branch, long item) {
		return jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE branch_id=? AND item_id=?", Long.class, branch, item);
	}

	private long createPo(MockHttpSession s, String json) throws Exception {
		String body = mvc.perform(post("/api/branch/purchase-orders").session(s).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	@Test
	void cashiersCannotSeeInventoryOrPurchaseOrdersButManagersCanSeeLowStock() throws Exception {
		mvc.perform(get("/api/branch/inventory").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/branch/purchase-orders").session(login("cashier"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/branch/inventory").session(login("manager")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name=='Caramel syrup (750 ml)')].low").value(true));
	}

	@Test
	void purchaseOrderGoesFromRequestThroughHqToStock() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		long beans = item("Espresso%");
		long milk = item("Fresh milk%");
		long branch = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		long before = stock(branch, beans);

		long po = createPo(manager, "{\"note\":\"weekly\",\"lines\":[{\"itemId\":" + beans + ",\"qty\":5},{\"itemId\":" + milk + ",\"qty\":10}]}");
		long beansLine = jdbc.queryForObject("SELECT id FROM branch_po_lines WHERE po_id=? AND item_id=?", Long.class, po, beans);

		// cannot receive what HQ has not sent
		mvc.perform(post("/api/branch/purchase-orders/" + po + "/receive").session(manager).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}")).andExpect(status().isConflict());

		// HQ approves 3 of 5 bags (milk in full); a second approval is refused; then it is sent
		mvc.perform(post("/api/admin/branch-pos/" + po + "/approve").session(admin).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"lineId\":" + beansLine + ",\"qty\":3}]}")).andExpect(status().isOk());
		mvc.perform(post("/api/admin/branch-pos/" + po + "/approve").session(admin).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}")).andExpect(status().isConflict());
		mvc.perform(post("/api/admin/branch-pos/" + po + "/ship").session(admin).with(csrf())).andExpect(status().isOk());

		// more than approved is refused and changes nothing
		mvc.perform(post("/api/branch/purchase-orders/" + po + "/receive").session(manager).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"lineId\":" + beansLine + ",\"qty\":4}]}")).andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT status FROM branch_pos WHERE id=?", String.class, po)).isEqualTo("SHIPPED");

		// 2 of the 3 bags arrive
		mvc.perform(post("/api/branch/purchase-orders/" + po + "/receive").session(manager).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[{\"lineId\":" + beansLine + ",\"qty\":2}]}")).andExpect(status().isOk());
		assertThat(stock(branch, beans) - before).isEqualTo(2);
		mvc.perform(get("/api/branch/purchase-orders/" + po).session(manager))
			.andExpect(jsonPath("$.po.status").value("RECEIVED"))
			.andExpect(jsonPath("$.lines.length()").value(2));
	}

	@Test
	void wasteCannotTakeStockBelowZero() throws Exception {
		var manager = login("manager");
		long straws = item("Straws%");
		mvc.perform(post("/api/branch/inventory/adjust").session(manager).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemId\":" + straws + ",\"delta\":100000,\"kind\":\"WASTE\",\"reason\":\"spill\"}"))
			.andExpect(status().isConflict());
		mvc.perform(post("/api/branch/inventory/adjust").session(manager).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemId\":" + straws + ",\"delta\":1,\"kind\":\"WASTE\",\"reason\":\"dropped a pack\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void hqRejectsWithAReasonAndBranchCanCancelOnlyAnUnreviewedOrder() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		String one = "{\"lines\":[{\"itemId\":" + item("Fresh milk%") + ",\"qty\":1}]}";
		long a = createPo(manager, one);
		long b = createPo(manager, one);
		mvc.perform(post("/api/admin/branch-pos/" + a + "/reject").session(admin).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Out of stock this week\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/branch/purchase-orders/" + a + "/cancel").session(manager).with(csrf())).andExpect(status().isConflict());
		mvc.perform(post("/api/branch/purchase-orders/" + b + "/cancel").session(manager).with(csrf())).andExpect(status().isOk());
		mvc.perform(get("/api/branch/purchase-orders/" + a).session(manager))
			.andExpect(jsonPath("$.rejectReason").value("Out of stock this week"));
	}
}
