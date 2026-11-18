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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OutsourceTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private ResultActions send(MockHttpSession s, String url, String json) throws Exception {
		return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	void branchRecordsLocalPurchasesStockedItemsAddToStockAndTotalsAddUp() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		mvc.perform(get("/api/branch/outsource").session(login("cashier"))).andExpect(status().isForbidden());
		long milk = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Fresh milk%'", Long.class);
		long branch = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		long before = jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE branch_id=? AND item_id=?", Long.class, branch, milk);

		// rules
		send(manager, "/api/branch/outsource", "{\"vendor\":\"Palengke\",\"paidWith\":\"CASH\",\"lines\":[]}").andExpect(status().isBadRequest());
		send(manager, "/api/branch/outsource", "{\"vendor\":\"Palengke\",\"paidWith\":\"CRYPTO\",\"lines\":[{\"description\":\"Ice\",\"qty\":1,\"unitCost\":\"50\"}]}").andExpect(status().isBadRequest());
		send(manager, "/api/branch/outsource", "{\"vendor\":\"Palengke\",\"paidWith\":\"CASH\",\"purchasedOn\":\"2999-01-01\",\"lines\":[{\"description\":\"Ice\",\"qty\":1,\"unitCost\":\"50\"}]}").andExpect(status().isBadRequest());
		send(manager, "/api/branch/outsource", "{\"vendor\":\"Palengke\",\"paidWith\":\"CASH\",\"lines\":[{\"qty\":1,\"unitCost\":\"50\"}]}").andExpect(status().isBadRequest());   // nothing says what it is

		// 3 L of milk at 95.50 (stocked) and 2 bags of ice at 60 (not a stocked item): 286.50 + 120.00
		String body = send(manager, "/api/branch/outsource", "{\"vendor\":\"Palengke\",\"receiptNo\":\"R-77\",\"paidWith\":\"CASH\",\"lines\":["
			+ "{\"itemId\":" + milk + ",\"qty\":3,\"unitCost\":\"95.50\"},{\"description\":\"Ice\",\"qty\":2,\"unitCost\":\"60\"}]}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();
		mvc.perform(get("/api/branch/outsource/" + id).session(manager)).andExpect(jsonPath("$.total").value(40650))
			.andExpect(jsonPath("$.lines.length()").value(2)).andExpect(jsonPath("$.vendor").value("Palengke"));
		assertThat(jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE branch_id=? AND item_id=?", Long.class, branch, milk) - before).isEqualTo(3000);

		// listed for the branch and for HQ; another branch's manager would not see it
		mvc.perform(get("/api/branch/outsource").session(manager)).andExpect(jsonPath("$[?(@.id==" + id + ")]").exists());
		mvc.perform(get("/api/admin/outsource").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id==" + id + ")]").exists());
		mvc.perform(get("/api/admin/outsource").session(manager)).andExpect(status().isForbidden());
		long other = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-02'", Long.class);
		jdbc.update("UPDATE outsource_purchases SET branch_id = ? WHERE id = ?", other, id);
		mvc.perform(get("/api/branch/outsource/" + id).session(manager)).andExpect(status().isNotFound());

		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason) VALUES (?, ?, -3000, 'ADJUST', 'test reset')", branch, milk);
	}

	@Test
	void hqChoosesWhichItemsBranchesCanOrder() throws Exception {
		var manager = login("manager");
		var admin = login("admin");
		long straws = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Straws%'", Long.class);
		mvc.perform(post("/api/admin/items/" + straws + "/orderable").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"orderable\":false}"))
			.andExpect(status().isForbidden());

		send(admin, "/api/admin/items/" + straws + "/orderable", "{\"orderable\":false}").andExpect(status().isOk());
		mvc.perform(get("/api/branch/inventory").session(manager)).andExpect(jsonPath("$[?(@.itemId==" + straws + ")].orderable").value(false));
		send(manager, "/api/branch/purchase-orders", "{\"lines\":[{\"itemId\":" + straws + ",\"qty\":1}]}").andExpect(status().isBadRequest());

		send(admin, "/api/admin/items/" + straws + "/orderable", "{\"orderable\":true}").andExpect(status().isOk());
		send(manager, "/api/branch/purchase-orders", "{\"lines\":[{\"itemId\":" + straws + ",\"qty\":1}]}").andExpect(status().isCreated());
		send(admin, "/api/admin/items/999999/orderable", "{\"orderable\":true}").andExpect(status().isNotFound());
	}
}
