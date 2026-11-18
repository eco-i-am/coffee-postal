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
class SupplierTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private MockHttpSession login(String user) throws Exception {
		var res = mvc.perform(formLogin("/api/auth/login").user(user).password("coffee123")).andReturn();
		return (MockHttpSession) res.getRequest().getSession(false);
	}

	private String body(org.springframework.test.web.servlet.ResultActions a) throws Exception { return a.andReturn().getResponse().getContentAsString(); }

	private org.springframework.test.web.servlet.ResultActions send(MockHttpSession s, String url, String json) throws Exception {
		return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	void supplierOrderIsReceivedInPartsStockGrowsAndWhatIsOwedAddsUp() throws Exception {
		var admin = login("admin");
		mvc.perform(get("/api/admin/suppliers").session(login("manager"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/supplier-pos").session(login("cashier"))).andExpect(status().isForbidden());

		String name = "Beanery " + System.nanoTime() % 100000;
		long sup = ((Number) JsonPath.read(body(send(admin, "/api/admin/suppliers", "{\"name\":\"" + name + "\",\"termsDays\":30}").andExpect(status().isCreated())), "$.id")).longValue();
		send(admin, "/api/admin/suppliers", "{\"name\":\"" + name.toUpperCase() + "\",\"termsDays\":0}").andExpect(status().isConflict());

		long beans = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Espresso%'", Long.class);
		long milk = jdbc.queryForObject("SELECT id FROM items WHERE name LIKE 'Fresh milk%'", Long.class);
		long br = jdbc.queryForObject("SELECT id FROM branches WHERE code='CEB-01'", Long.class);
		long before = jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE branch_id=? AND item_id=?", Long.class, br, beans);

		// rules on creating
		send(admin, "/api/admin/supplier-pos", "{\"supplierId\":" + sup + ",\"branchId\":" + br + ",\"lines\":[]}").andExpect(status().isBadRequest());
		send(admin, "/api/admin/supplier-pos", "{\"supplierId\":" + sup + ",\"branchId\":" + br + ",\"lines\":[{\"itemId\":" + beans + ",\"qty\":0,\"unitCost\":\"100\"}]}").andExpect(status().isBadRequest());

		// 10 bags of beans at 800.00 and 20 of milk at 95.50  -> 8,000.00 + 1,910.00
		String created = body(send(admin, "/api/admin/supplier-pos", "{\"supplierId\":" + sup + ",\"branchId\":" + br + ",\"note\":\"weekly\",\"lines\":["
			+ "{\"itemId\":" + beans + ",\"qty\":10,\"unitCost\":\"800\"},{\"itemId\":" + milk + ",\"qty\":20,\"unitCost\":\"95.50\"}]}").andExpect(status().isCreated()));
		long po = ((Number) JsonPath.read(created, "$.id")).longValue();
		mvc.perform(get("/api/admin/supplier-pos/" + po).session(admin)).andExpect(jsonPath("$.ordered").value(991000)).andExpect(jsonPath("$.received").value(0));
		long beanLine = jdbc.queryForObject("SELECT id FROM supplier_po_lines WHERE po_id=? AND item_id=?", Long.class, po, beans);
		long milkLine = jdbc.queryForObject("SELECT id FROM supplier_po_lines WHERE po_id=? AND item_id=?", Long.class, po, milk);

		// first delivery: 4 bags. Stock grows by 4, status PARTIAL, 3,200.00 is now owed
		send(admin, "/api/admin/supplier-pos/" + po + "/receive", "{\"lines\":[{\"lineId\":" + beanLine + ",\"qty\":4}]}").andExpect(status().isOk());
		long mid = jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE branch_id=? AND item_id=?", Long.class, br, beans);
		assertThat(mid).isEqualTo(before + 4000);
		mvc.perform(get("/api/admin/supplier-pos/" + po).session(admin)).andExpect(jsonPath("$.status").value("PARTIAL")).andExpect(jsonPath("$.received").value(320000));

		// cannot receive more than is still due, and a failed receive changes nothing
		send(admin, "/api/admin/supplier-pos/" + po + "/receive", "{\"lines\":[{\"lineId\":" + beanLine + ",\"qty\":7}]}").andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("SELECT coalesce(sum(qty),0) FROM stock_movements WHERE branch_id=? AND item_id=?", Long.class, br, beans)).isEqualTo(mid);
		// a PO that has something received cannot be cancelled
		send(admin, "/api/admin/supplier-pos/" + po + "/cancel", "{}").andExpect(status().isConflict());

		// payments: up to the order total, not beyond
		send(admin, "/api/admin/supplier-pos/" + po + "/payments", "{\"amount\":\"3000\",\"method\":\"BANK\",\"reference\":\"TXN1\"}").andExpect(status().isCreated());
		send(admin, "/api/admin/supplier-pos/" + po + "/payments", "{\"amount\":\"7000\",\"method\":\"BANK\"}").andExpect(status().isConflict());
		send(admin, "/api/admin/supplier-pos/" + po + "/payments", "{\"amount\":\"100\",\"method\":\"BITCOIN\"}").andExpect(status().isBadRequest());
		// owed = received 3,200.00 - paid 3,000.00 = 200.00
		mvc.perform(get("/api/admin/suppliers").session(admin)).andExpect(jsonPath("$[?(@.id==" + sup + ")].owed").value(20000));

		// the rest of the beans, and the milk; the order completes
		send(admin, "/api/admin/supplier-pos/" + po + "/receive", "{\"lines\":[{\"lineId\":" + beanLine + ",\"qty\":6},{\"lineId\":" + milkLine + ",\"qty\":20}]}").andExpect(status().isOk());
		mvc.perform(get("/api/admin/supplier-pos/" + po).session(admin)).andExpect(jsonPath("$.status").value("RECEIVED")).andExpect(jsonPath("$.balance").value(991000 - 300000));
		send(admin, "/api/admin/supplier-pos/" + po + "/receive", "{\"lines\":[{\"lineId\":" + beanLine + ",\"qty\":1}]}").andExpect(status().isConflict());

		// short delivery: ordered 5, got 2, supplier will not send more -> closed at 2
		String c2 = body(send(admin, "/api/admin/supplier-pos", "{\"supplierId\":" + sup + ",\"branchId\":" + br + ",\"lines\":[{\"itemId\":" + beans + ",\"qty\":5,\"unitCost\":\"800\"}]}"));
		long po2 = ((Number) JsonPath.read(c2, "$.id")).longValue();
		long line2 = jdbc.queryForObject("SELECT id FROM supplier_po_lines WHERE po_id=?", Long.class, po2);
		send(admin, "/api/admin/supplier-pos/" + po2 + "/close", "{}").andExpect(status().isConflict());   // nothing received yet
		send(admin, "/api/admin/supplier-pos/" + po2 + "/receive", "{\"lines\":[{\"lineId\":" + line2 + ",\"qty\":2}]}").andExpect(status().isOk());
		send(admin, "/api/admin/supplier-pos/" + po2 + "/close", "{}").andExpect(status().isOk());
		mvc.perform(get("/api/admin/supplier-pos/" + po2).session(admin)).andExpect(jsonPath("$.status").value("RECEIVED")).andExpect(jsonPath("$.received").value(160000));

		// an untouched order can be cancelled
		String c3 = body(send(admin, "/api/admin/supplier-pos", "{\"supplierId\":" + sup + ",\"branchId\":" + br + ",\"lines\":[{\"itemId\":" + milk + ",\"qty\":1,\"unitCost\":\"90\"}]}"));
		long po3 = ((Number) JsonPath.read(c3, "$.id")).longValue();
		send(admin, "/api/admin/supplier-pos/" + po3 + "/cancel", "{}").andExpect(status().isOk());

		// the stock these receipts added is taken back out so other tests see the usual numbers
		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason) VALUES (?, ?, ?, 'ADJUST', 'test reset')", br, beans, -12000);
		jdbc.update("INSERT INTO stock_movements(branch_id, item_id, qty, kind, reason) VALUES (?, ?, ?, 'ADJUST', 'test reset')", br, milk, -20000);
		jdbc.update("UPDATE suppliers SET active = FALSE WHERE id = ?", sup);
	}
}
